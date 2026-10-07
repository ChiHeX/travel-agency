package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导游"即将出发"筛选一致性（C-03）与日期时区口径（C-05）契约回归测试。
 *
 * <p>验证工作台 {@code upcoming} 与列表 {@code scope=UPCOMING} 采用同一定义：
 * 排除行程中、已完成、已取消，且出发日期不早于当天（当天算"未出发"，仍属于即将出发）。
 * 需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。用例在事务内执行，结束后自动回滚。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class GuideUpcomingContractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired TravelRouteMapper routes;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;
    @Autowired DepartureMapper departures;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    private MockMvc mvc;
    private String token;
    private Long guideId;
    private Long routeId;

    private Long openFutureId;
    private Long openTodayId;
    private Long openPastId;
    private Long cancelledFutureId;
    private Long finishedFutureId;
    private Long travellingFutureId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TravelRoute route = new TravelRoute();
        route.name = "即将出发契约线路";
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 6;
        route.status = "PUBLISHED";
        route.ratingAvg = new BigDecimal("0.00");
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        routeId = route.id;

        Guide guide = guide();
        guideId = guide.id;
        token = "Bearer " + tokens.createToken(guide.userId, "guide_up_" + guide.id, Set.of("GUIDE"));

        openFutureId = departure("OPEN", LocalDate.now().plusDays(10)).id;
        openTodayId = departure("OPEN", LocalDate.now()).id;
        openPastId = departure("OPEN", LocalDate.now().minusDays(2)).id;
        cancelledFutureId = departure("CANCELLED", LocalDate.now().plusDays(5)).id;
        finishedFutureId = departure("FINISHED", LocalDate.now().plusDays(5)).id;
        travellingFutureId = departure("TRAVELLING", LocalDate.now().plusDays(5)).id;
    }

    @Test
    void upcomingScopeKeepsOnlyNotDepartedNonCancelled() throws Exception {
        var response = mvc.perform(get("/api/guide/departures").header("Authorization", token)
                        .param("scope", "UPCOMING").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andReturn().getResponse();

        Set<String> ids = itemIds(json.readTree(response.getContentAsString()).get("data").get("items"));
        assertTrue(ids.contains(String.valueOf(openFutureId)), "未来可报名团期应出现");
        assertTrue(ids.contains(String.valueOf(openTodayId)), "当天出发仍算即将出发");
        assertFalse(ids.contains(String.valueOf(openPastId)), "已过出发日期的团期不应出现");
        assertFalse(ids.contains(String.valueOf(cancelledFutureId)), "已取消团期不应出现");
        assertFalse(ids.contains(String.valueOf(finishedFutureId)), "已完成团期不应出现");
        assertFalse(ids.contains(String.valueOf(travellingFutureId)), "行程中团期不应出现");
    }

    @Test
    void dashboardUpcomingUsesSameDefinitionAsList() throws Exception {
        var dashboard = mvc.perform(get("/api/guide/dashboard").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Set<String> dashboardIds = itemIds(json.readTree(dashboard.getContentAsString())
                .get("data").get("upcoming"));

        var list = mvc.perform(get("/api/guide/departures").header("Authorization", token)
                        .param("scope", "UPCOMING").param("size", "50"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Set<String> listIds = itemIds(json.readTree(list.getContentAsString()).get("data").get("items"));

        assertEquals(listIds, dashboardIds, "工作台 upcoming 与列表 scope=UPCOMING 必须是同一集合");
    }

    private static Set<String> itemIds(JsonNode items) {
        Set<String> ids = new LinkedHashSet<>();
        items.forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private Departure departure(String status, LocalDate startDate) {
        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = startDate;
        departure.endDate = startDate.plusDays(5);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = 20;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 0;
        departure.guideId = guideId;
        departure.status = status;
        departure.version = 0;
        departures.insert(departure);
        return departure;
    }

    private Guide guide() {
        SysUser user = new SysUser();
        user.username = "guide_up_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.nickname = "即将出发测试导游";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);

        Guide guide = new Guide();
        guide.userId = user.id;
        guide.name = "测试导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        return guide;
    }
}
