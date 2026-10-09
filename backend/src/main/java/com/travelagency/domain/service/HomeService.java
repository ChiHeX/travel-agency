package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.RouteStatus;
import com.travelagency.domain.dto.HomeView;
import com.travelagency.domain.dto.RouteSummaryView;
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
import java.util.stream.Stream;

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
        // 用它排行会与真实订单不符。
        var popular = routeService.popularRoutes(8, null);
        var recommended = routes.selectList(published().orderByDesc("rating_avg", "rating_count", "created_at")
                .orderByAsc("id").last("LIMIT 8"));
        var upcoming = routes.selectUpcomingRoutes(RouteStatus.PUBLISHED, DepartureStatus.OPEN);
        // 三个区块的起价与最近团期都取「仍可报名」的团期（OPEN + 未过期 + 有余位）。
        // 不直接用 RouteService 摘要里的 minAdultPrice / nextDepartureDate：那两个字段口径更宽
        // （价格不看出发日期、最近团期不看余位），会让同一条线路在"热门"与"推荐 / 近期"区块
        // 显示不同的价格或日期，甚至给出已经无法报名的起价。
        var routeIds = Stream.concat(
                        popular.stream().map(RouteSummaryView::id),
                        Stream.concat(recommended.stream(), upcoming.stream()).map(route -> route.id))
                .distinct()
                .toList();
        Map<Long, List<Departure>> departuresByRoute = availableDepartures(routeIds);
        return new HomeView(routes.popularDestinations(), summaryViews(popular, departuresByRoute),
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
        return items.stream()
                .map(route -> HomeView.Route.from(route,
                        minAdultPrice(byRoute.get(route.id)), nextStartDate(byRoute.get(route.id))))
                .toList();
    }

    /** 热门线路区块：排行与计数来自实时统计，价格与最近团期与其它区块同一口径。 */
    private List<HomeView.Route> summaryViews(List<RouteSummaryView> items, Map<Long, List<Departure>> byRoute) {
        return items.stream()
                .map(route -> HomeView.Route.from(route,
                        minAdultPrice(byRoute.get(route.id())), nextStartDate(byRoute.get(route.id()))))
                .toList();
    }

    /** 仍可报名团期的最低价；没有可报名团期时为 null。 */
    private static BigDecimal minAdultPrice(List<Departure> available) {
        return available == null ? null
                : available.stream().map(departure -> departure.adultPrice)
                        .min(BigDecimal::compareTo).orElse(null);
    }

    /** 仍可报名团期中最早的出发日期；没有可报名团期时为 null。 */
    private static LocalDate nextStartDate(List<Departure> available) {
        return available == null ? null
                : available.stream().map(departure -> departure.startDate)
                        .min(LocalDate::compareTo).orElse(null);
    }
}
