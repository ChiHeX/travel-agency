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
    }
}
