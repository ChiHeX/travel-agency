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
import com.travelagency.domain.service.DepartureService;
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
    private Long draftFutureId;

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

        // 夹具日期一律取【库内当天】：生产判断用的是 CURRENT_DATE()，
        // 用 LocalDate.now() 时只要 JVM 与库会话时区不同，跨午夜前后就会错开一天，
        // "当天出发仍算即将出发"这类边界断言会凭空变红。
        LocalDate today = departures.databaseToday();
        openFutureId = departure("OPEN", today.plusDays(10)).id;
        openTodayId = departure("OPEN", today).id;
        openPastId = departure("OPEN", today.minusDays(2)).id;
        cancelledFutureId = departure("CANCELLED", today.plusDays(5)).id;
        finishedFutureId = departure("FINISHED", today.plusDays(5)).id;
        travellingFutureId = departure("TRAVELLING", today.plusDays(5)).id;
        // 草稿团期还没上架，导游点进去「开始行程」必然 409，因此不该出现在"即将出发"里。
        draftFutureId = departure("DRAFT", today.plusDays(5)).id;
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
        assertFalse(ids.contains(String.valueOf(draftFutureId)),
                "草稿团期不应出现（它与「开始行程」允许的状态集合必须一致，否则点进去只有 409）");
    }

    /** 「即将出发」列出的团期，状态必须都是「可以开始行程」的，否则按钮与列表会互相打脸。 */
    @Test
    void upcomingListMatchesStartableStatuses() throws Exception {
        var response = mvc.perform(get("/api/guide/departures").header("Authorization", token)
                        .param("scope", "UPCOMING").param("size", "50"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        var items = json.readTree(response.getContentAsString()).get("data").get("items");
        assertTrue(items.size() > 0, "前置条件：夹具里应有即将出发的团期");
        for (var item : items) {
            assertTrue(DepartureService.STARTABLE_STATUSES.contains(item.get("status").asText()),
                    "即将出发里的团期都应可以开始行程，实际状态：" + item.get("status").asText());
        }
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
