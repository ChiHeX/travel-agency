package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.RouteStatus;
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
    private final RouteService routeService;

    public HomeService(TravelRouteMapper routes, DepartureMapper departures, RouteService routeService) {
        this.routes = routes;
        this.departures = departures;
        this.routeService = routeService;
    }

    public HomeView get() {
        // 热门线路复用后台工作台同一套实时口径（按有效报名订单条数，PRD §27），
        // 不再读 travel_route.valid_booking_count —— 那一列可能被预置或被绕过业务链路写入，
        // 用它排行会与真实订单不符。minAdultPrice / nextDepartureDate 由摘要一并返回。
        var popular = routeService.popularRoutes(8, null).stream()
                .map(HomeView.Route::from)
                .toList();
        var recommended = routes.selectList(published().orderByDesc("rating_avg", "rating_count", "created_at")
                .orderByAsc("id").last("LIMIT 8"));
        var upcoming = routes.selectUpcomingRoutes(RouteStatus.PUBLISHED, DepartureStatus.OPEN);
        var routeIds = List.of(recommended, upcoming).stream()
                .flatMap(List::stream)
                .map(route -> route.id)
                .distinct()
                .toList();
        Map<Long, List<Departure>> departuresByRoute = availableDepartures(routeIds);
        return new HomeView(routes.popularDestinations(), popular,
                views(recommended, departuresByRoute), views(upcoming, departuresByRoute));
    }

    private QueryWrapper<TravelRoute> published() {
        return new QueryWrapper<TravelRoute>().eq("status", RouteStatus.PUBLISHED).eq("deleted", 0);
    }

    private Map<Long, List<Departure>> availableDepartures(List<Long> routeIds) {
        if (routeIds.isEmpty()) return Map.of();
        return departures.selectList(new QueryWrapper<Departure>()
                .in("route_id", routeIds)
                .eq("status", DepartureStatus.OPEN).apply("start_date >= CURRENT_DATE()")
                .apply("reserved_people + confirmed_people < max_people"))
                .stream().collect(Collectors.groupingBy(departure -> departure.routeId));
    }

    private List<HomeView.Route> views(List<TravelRoute> items, Map<Long, List<Departure>> byRoute) {
        return items.stream().map(route -> {
            var available = byRoute.getOrDefault(route.id, List.of());
            BigDecimal price = available.stream().map(d -> d.adultPrice).min(BigDecimal::compareTo).orElse(null);
            LocalDate next = available.stream().map(d -> d.startDate).min(LocalDate::compareTo).orElse(null);
            return HomeView.Route.from(route, price, next);
        }).toList();
    }
}
