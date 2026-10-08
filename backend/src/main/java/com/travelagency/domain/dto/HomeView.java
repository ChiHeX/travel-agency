package com.travelagency.domain.dto;

import com.travelagency.domain.entity.TravelRoute;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record HomeView(List<Destination> popularDestinations, List<Route> popularRoutes,
                       List<Route> recommendedRoutes, List<Route> upcomingRoutes) {
    public record Destination(String destination, long validBookingCount) {}

    public record Route(Long id, String name, String departureCity, String destination, Integer durationDays,
                        String description, String coverUrl, BigDecimal minAdultPrice, LocalDate nextDepartureDate,
                        BigDecimal ratingAvg, Integer ratingCount, Integer validBookingCount, String status) {
        public static Route from(TravelRoute route, BigDecimal price, LocalDate nextDate) {
            return new Route(route.id, route.name, route.departureCity, route.destination, route.durationDays,
                    route.description, route.coverUrl, price, nextDate, route.ratingAvg, route.ratingCount,
                    route.validBookingCount, route.status);
        }

        /**
         * 由公开线路摘要（{@link RouteSummaryView}）转换：排名与计数直接复用
         * {@code RouteService#popularRoutes} 的实时统计结果，避免首页再走一遍物化计数列。
         *
         * <p>价格与最近团期由调用方传入，而<b>不</b>取 {@code route.minAdultPrice()} /
         * {@code route.nextDepartureDate()}：摘要里那两个字段的口径更宽（价格不看出发日期、
         * 最近团期不看余位），与首页"推荐 / 近期团期"区块的「仍可报名」口径不同，
         * 直接用会让同一条线路在同一页面显示两个价格。</p>
         */
        public static Route from(RouteSummaryView route, BigDecimal price, LocalDate nextDate) {
            return new Route(route.id(), route.name(), route.departureCity(), route.destination(),
                    route.durationDays(), route.description(), route.coverUrl(), price,
                    nextDate, route.ratingAvg(), route.ratingCount(),
                    route.validBookingCount(), route.status());
        }
    }
}
