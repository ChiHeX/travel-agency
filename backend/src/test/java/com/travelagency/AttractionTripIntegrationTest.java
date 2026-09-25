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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        Departure available = departure(matching.id, LocalDate.now().plusDays(5), "OPEN", 1);
        Departure full = departure(matching.id, LocalDate.now().plusDays(8), "OPEN", 0);
        departure(matching.id, LocalDate.now().plusDays(10), "CLOSED", 1);
        departure(matching.id, LocalDate.now().minusDays(2), "OPEN", 1);
        departure(unrelated.id, LocalDate.now().plusDays(5), "OPEN", 1);
        departure(draft.id, LocalDate.now().plusDays(5), "OPEN", 1);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String body = mvc.perform(get("/api/attractions/{id}", selected.id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).path("data");

        assertEquals(selected.id.toString(), data.path("attraction").path("id").asText());
        assertEquals(2, data.path("departures").size());
        assertEquals(available.id.toString(), data.path("departures").get(0).path("id").asText());
        assertEquals(1, data.path("departures").get(0).path("availableSeats").asInt());
        assertEquals(full.id.toString(), data.path("departures").get(1).path("id").asText());
        assertEquals(0, data.path("departures").get(1).path("availableSeats").asInt());
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
