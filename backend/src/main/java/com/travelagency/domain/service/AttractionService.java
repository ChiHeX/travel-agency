package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.AttractionDetailView;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AttractionService {
    private final AttractionMapper attractions;
    private final RouteItineraryItemMapper itineraryItems;
    private final TravelRouteMapper routes;
    private final DepartureMapper departures;

    public AttractionService(AttractionMapper attractions, RouteItineraryItemMapper itineraryItems,
                             TravelRouteMapper routes, DepartureMapper departures) {
        this.attractions = attractions;
        this.itineraryItems = itineraryItems;
        this.routes = routes;
        this.departures = departures;
    }

    public AttractionDetailView detail(Long attractionId) {
        Attraction attraction = attractions.selectById(attractionId);
        if (attraction == null || !Integer.valueOf(1).equals(attraction.status)) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "地点不存在");
        }
        List<Long> routeIds = itineraryItems.publishedRouteIdsForAttraction(attractionId);
        if (routeIds.isEmpty()) return new AttractionDetailView(AttractionDetailView.Place.from(attraction), List.of());

        Map<Long, TravelRoute> routeById = routes.selectBatchIds(routeIds).stream()
                .collect(Collectors.toMap(route -> route.id, Function.identity()));
        List<AttractionDetailView.Trip> trips = departures.selectList(new QueryWrapper<Departure>()
                        .in("route_id", routeIds).eq("status", "OPEN")
                        .ge("start_date", LocalDate.now()).orderByAsc("start_date", "id"))
                .stream().filter(departure -> routeById.containsKey(departure.routeId))
                .map(departure -> {
                    TravelRoute route = routeById.get(departure.routeId);
                    return new AttractionDetailView.Trip(departure.id, route.id, route.name,
                            route.departureCity, departure.startDate, departure.endDate,
                            departure.adultPrice, departure.childPrice,
                            DepartureView.availableSeats(departure));
                }).toList();
        return new AttractionDetailView(AttractionDetailView.Place.from(attraction), trips);
    }
}
