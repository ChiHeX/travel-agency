package com.travelagency;

import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
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

import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 景点公开浏览端点的数据库集成测试（需要 {@code TRAVEL_MYSQL_TEST=true}）：
 * 覆盖"必须在真库上才成立"的部分 —— 详情只列出已发布线路的未来开放团期、
 * 剩余名额按 {@code max(0, maxPeople - reserved - confirmed)} 计算，
 * 以及公开列表 {@code city} 契约上限（64 码点）两侧的边界。
 *
 * <p>参数校验本身不需要数据库，由 {@code AttractionPublicWebContractTest} 覆盖；
 * 本类只负责"校验通过之后"的行为。</p>
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class AttractionTripIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AttractionMapper attractions;
    @Autowired TravelRouteMapper routes;
    @Autowired RouteItineraryDayMapper days;
    @Autowired RouteItineraryItemMapper items;
    @Autowired DepartureMapper departures;
    @Autowired JsonMapper json;

    @Test
    void publicPlaceShowsOnlyUpcomingOpenTripsFromRoutesContainingItsAttractionId() throws Exception {
        Attraction selected = place("关联地点");
        Attraction other = place("其他地点");
        TravelRoute matching = route("关联线路", "PUBLISHED", selected.id);
        TravelRoute unrelated = route("其他线路", "PUBLISHED", other.id);
        TravelRoute draft = route("未发布线路", "DRAFT", selected.id);
        // 夹具日期取【库内当天】：生产过滤用的是 CURRENT_DATE()，用 LocalDate.now()
        // 会在 JVM 与库会话时区不一致时错开一天，让"未来/已过期"的边界断言随机变红。
        LocalDate today = departures.databaseToday();
        Departure available = departure(matching.id, today.plusDays(5), "OPEN", 1);
        Departure full = departure(matching.id, today.plusDays(8), "OPEN", 0);
        departure(matching.id, today.plusDays(10), "CLOSED", 1);
        departure(matching.id, today.minusDays(2), "OPEN", 1);
        departure(unrelated.id, today.plusDays(5), "OPEN", 1);
        departure(draft.id, today.plusDays(5), "OPEN", 1);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String body = mvc.perform(get("/api/attractions/{id}", selected.id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).path("data");

        assertEquals(selected.id.toString(), data.path("attraction").path("id").asText());
        assertEquals(2, data.path("departures").size());
        assertEquals(available.id.toString(), data.path("departures").get(0).path("id").asText());
        assertEquals(1, data.path("departures").get(0).path("availableSeats").asInt());
        assertEquals("499.00", data.path("departures").get(0).path("childPrice").asText());
        assertEquals(full.id.toString(), data.path("departures").get(1).path("id").asText());
        assertEquals(0, data.path("departures").get(1).path("availableSeats").asInt());
    }

    /**
     * 公开列表 {@code city} 的契约上限（{@code maxLength: 64}）在真实服务上的两侧边界：
     * 恰好 64 码点必须照旧返回 200，65 码点返回 422。
     *
     * <p>{@code AttractionPublicWebContractTest} 不需要数据库，只能断言"超长被拒"；
     * 若把上限写成比 64 更小的值，那些用例仍会通过。这里是唯一能挡住"误伤合法请求"的地方 ——
     * 校验通过后查询要真的落到数据库、结果要真的按分页信封序列化出来。</p>
     */
    @Test
    void publicListAcceptsCityAtTheContractLimitAndRejectsOneOverIt() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(get("/api/attractions").param("page", "1").param("size", "5")
                        .param("city", "城".repeat(64)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/attractions").param("page", "1").param("size", "5")
                        .param("city", "城".repeat(65)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("city")));
    }

    private Attraction place(String name) {
        Attraction place = new Attraction();
        place.name = name;
        place.city = "测试城市";
        place.status = 1;
        attractions.insert(place);
        return place;
    }

    private TravelRoute route(String name, String status, Long attractionId) {
        TravelRoute route = new TravelRoute();
        route.name = name;
        route.departureCity = "测试出发地";
        route.destination = "测试目的地";
        route.durationDays = 3;
        route.status = status;
        route.ratingAvg = BigDecimal.ZERO;
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        RouteItineraryDay day = new RouteItineraryDay();
        day.routeId = route.id;
        day.dayNumber = 1;
        day.title = "游览";
        days.insert(day);
        RouteItineraryItem item = new RouteItineraryItem();
        item.dayId = day.id;
        item.sortNo = 1;
        item.itemType = "ATTRACTION";
        item.name = name;
        item.attractionId = attractionId;
        items.insert(item);
        return route;
    }

    private Departure departure(Long routeId, LocalDate start, String status, int seats) {
        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = start;
        departure.endDate = start.plusDays(3);
        departure.adultPrice = new BigDecimal("899.00");
        departure.childPrice = new BigDecimal("499.00");
        departure.maxPeople = 1;
        departure.reservedPeople = 1 - seats;
        departure.confirmedPeople = 0;
        departure.status = status;
        departure.version = 0;
        departures.insert(departure);
        return departure;
    }
}
