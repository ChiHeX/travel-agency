package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
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
 * 导游端团期接口的契约回归测试（P1）。
 *
 * <p>冻结契约要求 {@code GET /guide/departures/{departureId}} 返回
 * {@code { departure, route, itinerary }}：导游必须能通过该接口看到每日行程，
 * 而游客名单由 {@code /passengers} 独立提供。同时覆盖列表分页信封与工作台键名。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。用例在事务内执行，结束后自动回滚。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class GuideTripContractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired TravelRouteMapper routes;
    @Autowired RouteItineraryDayMapper days;
    @Autowired RouteItineraryItemMapper items;
    @Autowired DepartureMapper departures;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    private MockMvc mvc;
    private String guideToken;
    private String otherGuideToken;
    private Long ownDepartureId;
    private Long foreignDepartureId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TravelRoute route = route();
        addItinerary(route.id);

        Guide own = guide();
        guideToken = "Bearer " + tokens.createToken(own.userId, "guide_" + own.id, Set.of("GUIDE"));
        ownDepartureId = departure(route.id, own.id).id;

        Guide other = guide();
        otherGuideToken = "Bearer " + tokens.createToken(other.userId, "guide_" + other.id, Set.of("GUIDE"));
        foreignDepartureId = departure(route.id, other.id).id;
    }

    @Test
    void guideDepartureDetailReturnsContractStructureWithItinerary() throws Exception {
        var response = mvc.perform(get("/api/guide/departures/" + ownDepartureId).header("Authorization", guideToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.departure.id").value(String.valueOf(ownDepartureId)))
                .andExpect(jsonPath("$.data.departure.availableSeats").isNumber())
                .andExpect(jsonPath("$.data.route.name").value("导游契约线路"))
                .andExpect(jsonPath("$.data.itinerary[0].dayNumber").value(1))
                .andExpect(jsonPath("$.data.itinerary[0].title").value("上海 → 昆明"))
                .andExpect(jsonPath("$.data.itinerary[0].items[0].name").value("大理古城"))
                .andReturn().getResponse();

        JsonNode data = json.readTree(response.getContentAsString()).get("data");
        // 契约 GuideDepartureDetail 为 additionalProperties:false：不能夹带 passengers，
        // 也不能直接输出数据库实体字段。
        assertFalse(data.has("passengers"), "游客名单由 /passengers 提供，不应出现在详情里");
        assertFalse(data.get("departure").has("version"), "departure 应为契约视图，不含实体字段");
        assertFalse(data.get("itinerary").get(0).has("createdAt"), "itinerary 应为契约视图");
        assertTrue(data.get("route").has("bookingNotice"), "route 应为契约 Route");
    }

    @Test
    void passengersStayOnTheirOwnEndpoint() throws Exception {
        mvc.perform(get("/api/guide/departures/" + ownDepartureId + "/passengers").header("Authorization", guideToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void otherGuidesDepartureIsForbiddenAndMissingOneIsNotFound() throws Exception {
        mvc.perform(get("/api/guide/departures/" + foreignDepartureId).header("Authorization", guideToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/guide/departures/9223372036854775807").header("Authorization", guideToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void guideDepartureListUsesContractPageEnvelope() throws Exception {
        mvc.perform(get("/api/guide/departures").header("Authorization", guideToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].availableSeats").isNumber());

        mvc.perform(get("/api/guide/departures").header("Authorization", guideToken).param("scope", "BAD"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void dashboardUsesContractKeysAndDepartureViews() throws Exception {
        var response = mvc.perform(get("/api/guide/dashboard").header("Authorization", guideToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.upcoming").isArray())
                .andExpect(jsonPath("$.data.current").isArray())
                .andExpect(jsonPath("$.data.history").isArray())
                .andReturn().getResponse();

        JsonNode data = json.readTree(response.getContentAsString()).get("data");
        assertFalse(data.has("active"), "契约键名是 current，不是 active");
        assertEquals(1, data.get("upcoming").size(), "未出发的团期归入 upcoming");
        assertTrue(data.get("upcoming").get(0).has("availableSeats"), "元素应为契约 Departure");
    }

    // ------------------------------------------------------------------
    // 测试数据
    // ------------------------------------------------------------------

    private TravelRoute route() {
        TravelRoute route = new TravelRoute();
        route.name = "导游契约线路";
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 1;
        route.status = "PUBLISHED";
        route.ratingAvg = new BigDecimal("0.00");
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        return route;
    }

    private void addItinerary(Long routeId) {
        RouteItineraryDay day = new RouteItineraryDay();
        day.routeId = routeId;
        day.dayNumber = 1;
        day.title = "上海 → 昆明";
        days.insert(day);

        RouteItineraryItem item = new RouteItineraryItem();
        item.dayId = day.id;
        item.sortNo = 1;
        item.itemType = "ATTRACTION";
        item.name = "大理古城";
        items.insert(item);
    }

    private Departure departure(Long routeId, Long guideId) {
        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(20);
        departure.endDate = LocalDate.now().plusDays(22);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = 20;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 0;
        departure.guideId = guideId;
        departure.status = "OPEN";
        departure.version = 0;
        departures.insert(departure);
        return departure;
    }

    private Guide guide() {
        SysUser user = new SysUser();
        user.username = "guide_ct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.nickname = "导游契约测试";
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
