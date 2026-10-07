package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.enums.RouteStatus;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.service.HomeService;
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

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-secret-with-at-least-32-bytes-entropy")
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class HomeContractIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired TravelRouteMapper routes;
    @Autowired DepartureMapper departures;
    @Autowired TravelOrderMapper orders;
    @Autowired SysUserMapper users;
    @Autowired HomeService homeService;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void homeIsPublicAndReturnsAllFrozenContractSections() throws Exception {
        routes.update(null, new UpdateWrapper<TravelRoute>().set("deleted", 1));

        TravelRoute route = new TravelRoute();
        route.name = "Contract home route";
        route.departureCity = "Contract departure";
        route.destination = "Contract destination";
        route.durationDays = 1;
        route.status = RouteStatus.PUBLISHED;
        route.ratingAvg = new BigDecimal("5.00");
        route.ratingCount = 1;
        route.validBookingCount = 2;
        route.deleted = 0;
        routes.insert(route);

        // 夹具日期取【库内当天】：首页"近期团期"用 CURRENT_DATE() 过滤，
        // 用 LocalDate.now() 会在 JVM 与库会话时区不一致时错开一天。
        LocalDate today = departures.databaseToday();
        Departure laterCheaperDeparture = departure(route.id, today.plusDays(2), new BigDecimal("100.00"));
        Departure nextDeparture = departure(route.id, today.plusDays(1), new BigDecimal("200.00"));
        departures.insert(laterCheaperDeparture);
        departures.insert(nextDeparture);

        // 热门目的地按"有效报名游客数量"统计（PRD §27），数据来自订单而不是线路上的
        // valid_booking_count（那一列是订单条数）：这里造一张已确认、1 成人 + 1 儿童的订单，
        // 该目的地的人数是 2。线路上的计数同时置为 2，用来区分"线路排行看计数、目的地排行看人数"。
        confirmedOrder(route.id, nextDeparture.id, 1, 1);

        var home = homeService.get();
        assertTrue(home.popularDestinations().stream()
                .anyMatch(item -> "Contract destination".equals(item.destination())
                        && item.validBookingCount() == 2));
        var upcomingRoute = home.upcomingRoutes().stream()
                .filter(item -> route.id.equals(item.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(new BigDecimal("100.00"), upcomingRoute.minAdultPrice());
        assertEquals(nextDeparture.startDate, upcomingRoute.nextDepartureDate());
        mvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.popularDestinations").isArray())
                .andExpect(jsonPath("$.data.popularDestinations[0].destination").value("Contract destination"))
                .andExpect(jsonPath("$.data.popularDestinations[0].validBookingCount").value(2))
                .andExpect(jsonPath("$.data.popularRoutes").isArray())
                .andExpect(jsonPath("$.data.recommendedRoutes").isArray())
                .andExpect(jsonPath("$.data.upcomingRoutes").isArray())
                .andExpect(jsonPath("$.data.upcomingRoutes[0].id").value(route.id.toString()))
                .andExpect(jsonPath("$.data.upcomingRoutes[0].minAdultPrice").value("100.00"))
                .andExpect(jsonPath("$.data.upcomingRoutes[0].nextDepartureDate")
                        .value(nextDeparture.startDate.toString()))
                .andExpect(jsonPath("$.data.upcomingRoutes[0].ratingAvg").value("5.00"))
                .andExpect(jsonPath("$.data.upcomingRoutes[0].validBookingCount").value(2))
                .andExpect(jsonPath("$.data.upcomingRoutes[0].status").value(RouteStatus.PUBLISHED));
    }

    private Departure departure(Long routeId, LocalDate startDate, BigDecimal adultPrice) {
        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = startDate;
        departure.endDate = startDate;
        departure.adultPrice = adultPrice;
        departure.childPrice = adultPrice;
        departure.maxPeople = 10;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 0;
        departure.status = DepartureStatus.OPEN;
        departure.version = 0;
        return departure;
    }

    /** 造一张"有效报名"的订单（已确认且未退款），供热门目的地的人数统计使用。 */
    private void confirmedOrder(Long routeId, Long departureId, int adultCount, int childCount) {
        SysUser buyer = new SysUser();
        buyer.username = "home_contract_buyer";
        buyer.passwordHash = "unused-test-hash";
        buyer.nickname = "首页契约测试游客";
        buyer.status = 1;
        buyer.deleted = 0;
        users.insert(buyer);

        TravelOrder order = new TravelOrder();
        order.orderNo = "TA-HOME-CONTRACT-1";
        order.userId = buyer.id;
        order.routeId = routeId;
        order.departureId = departureId;
        order.contactName = "测试联系人";
        order.contactPhone = "13800138000";
        order.adultCount = adultCount;
        order.childCount = childCount;
        order.adultUnitPrice = new BigDecimal("100.00");
        order.childUnitPrice = new BigDecimal("100.00");
        order.totalAmount = new BigDecimal("100.00")
                .multiply(BigDecimal.valueOf(adultCount + childCount));
        order.status = OrderStatus.CONFIRMED;
        order.paymentStatus = PaymentStatus.PAID;
        orders.insert(order);
    }
}
