package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.domain.dto.HomeView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HomeService {
    private final TravelRouteMapper routes;
    private final DepartureMapper departures;

    public HomeService(TravelRouteMapper routes, DepartureMapper departures) {
        this.routes = routes;
        this.departures = departures;
    }

    public HomeView get() {
        var popular = routes.selectList(published().gt("valid_booking_count", 0)
                .orderByDesc("valid_booking_count").orderByAsc("id").last("LIMIT 8"));
        var recommended = routes.selectList(published().orderByDesc("rating_avg", "rating_count", "created_at")
                .orderByAsc("id").last("LIMIT 8"));
        var upcoming = routes.selectList(published().apply("EXISTS (SELECT 1 FROM departure d "
                + "WHERE d.route_id = travel_route.id AND d.status = 'OPEN' AND d.start_date >= CURRENT_DATE() "
                + "AND d.reserved_people + d.confirmed_people < d.max_people)")
                .orderByAsc("(SELECT MIN(d.start_date) FROM departure d WHERE d.route_id = travel_route.id "
                        + "AND d.status = 'OPEN' AND d.start_date >= CURRENT_DATE() "
                        + "AND d.reserved_people + d.confirmed_people < d.max_people)", "id").last("LIMIT 8"));
        return new HomeView(routes.popularDestinations(), views(popular), views(recommended), views(upcoming));
    }

    private QueryWrapper<TravelRoute> published() {
        return new QueryWrapper<TravelRoute>().eq("status", "PUBLISHED").eq("deleted", 0);
    }

    private List<HomeView.Route> views(List<TravelRoute> items) {
        if (items.isEmpty()) return List.of();
        Map<Long, List<Departure>> byRoute = departures.selectList(new QueryWrapper<Departure>()
                .in("route_id", items.stream().map(route -> route.id).toList())
                .eq("status", "OPEN").ge("start_date", LocalDate.now())
                .apply("reserved_people + confirmed_people < max_people"))
                .stream().collect(Collectors.groupingBy(departure -> departure.routeId));
        return items.stream().map(route -> {
            var available = byRoute.getOrDefault(route.id, List.of());
            BigDecimal price = available.stream().map(d -> d.adultPrice).min(BigDecimal::compareTo).orElse(null);
            LocalDate next = available.stream().map(d -> d.startDate).min(LocalDate::compareTo).orElse(null);
            return HomeView.Route.from(route, price, next);
        }).toList();
    }
}
