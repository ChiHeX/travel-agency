package com.travelagency.domain.service;

import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.AttractionDetailView;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttractionServiceTest {
    private final AttractionMapper attractions = mock(AttractionMapper.class);
    private final RouteItineraryItemMapper itineraryItems = mock(RouteItineraryItemMapper.class);
    private final TravelRouteMapper routes = mock(TravelRouteMapper.class);
    private final DepartureMapper departures = mock(DepartureMapper.class);
    private final AttractionService service = new AttractionService(attractions, itineraryItems, routes, departures);

    @Test
    void hiddenPlaceDoesNotExposeItsTrips() {
        Attraction hidden = new Attraction();
        hidden.id = 3L;
        hidden.status = 0;
        when(attractions.selectById(3L)).thenReturn(hidden);

        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(3L)).getStatus());
        verify(itineraryItems, never()).publishedRouteIdsForAttraction(3L);
    }

    @Test
    void returnsAllRelatedOpenTripsWithAvailability() {
        Attraction place = new Attraction();
        place.id = 3L;
        place.status = 1;
        TravelRoute route = new TravelRoute();
        route.id = 7L;
        route.name = "大理行程";
        route.departureCity = "昆明";
        Departure first = trip(21L, 7L, 10, 2, 3);
        Departure full = trip(22L, 7L, 10, 4, 6);
        when(attractions.selectById(3L)).thenReturn(place);
        when(itineraryItems.publishedRouteIdsForAttraction(3L)).thenReturn(List.of(7L));
        when(routes.selectBatchIds(List.of(7L))).thenReturn(List.of(route));
        when(departures.selectList(ArgumentMatchers.any())).thenReturn(List.of(first, full));

        AttractionDetailView detail = service.detail(3L);

        assertEquals(List.of(21L, 22L), detail.departures().stream().map(AttractionDetailView.Trip::id).toList());
        assertEquals(List.of(5, 0), detail.departures().stream()
                .map(AttractionDetailView.Trip::availableSeats).toList());
        assertEquals("大理行程", detail.departures().get(0).routeName());
    }

    private static Departure trip(Long id, Long routeId, int capacity, int reserved, int confirmed) {
        Departure departure = new Departure();
        departure.id = id;
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(3);
        departure.endDate = LocalDate.now().plusDays(5);
        departure.adultPrice = new BigDecimal("899.00");
        departure.maxPeople = capacity;
        departure.reservedPeople = reserved;
        departure.confirmedPeople = confirmed;
        return departure;
    }
}
