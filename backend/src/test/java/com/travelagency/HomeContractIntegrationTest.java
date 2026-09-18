package com.travelagency;

import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.RouteStatus;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
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
    @Autowired HomeService homeService;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void homeIsPublicAndReturnsAllFrozenContractSections() throws Exception {
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

        Departure laterCheaperDeparture = departure(route.id, LocalDate.now().plusDays(2), new BigDecimal("100.00"));
        Departure nextDeparture = departure(route.id, LocalDate.now().plusDays(1), new BigDecimal("200.00"));
        departures.insert(laterCheaperDeparture);
        departures.insert(nextDeparture);

        var home = homeService.get();
        assertTrue(home.popularDestinations().stream()
                .anyMatch(item -> "Contract destination".equals(item.destination())
                        && item.validBookingCount() == 2));
        assertEquals(route.id, home.upcomingRoutes().getFirst().id());
        assertEquals(new BigDecimal("100.00"), home.upcomingRoutes().getFirst().minAdultPrice());
        assertEquals(nextDeparture.startDate, home.upcomingRoutes().getFirst().nextDepartureDate());
        mvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.popularDestinations").isArray())
                .andExpect(jsonPath("$.data.popularRoutes").isArray())
                .andExpect(jsonPath("$.data.recommendedRoutes").isArray())
                .andExpect(jsonPath("$.data.upcomingRoutes").isArray());
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
}
