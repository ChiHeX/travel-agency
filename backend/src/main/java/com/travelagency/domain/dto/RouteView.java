package com.travelagency.domain.dto;

import com.travelagency.domain.entity.TravelRoute;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 线路完整视图，对齐契约 {@code Route}（RouteSummary + 线路说明类字段，additionalProperties: false）。
 *
 * <p>用于后台线路的新增、修改、上下架和详情响应，不直接暴露 {@link TravelRoute} 实体。</p>
 */
public record RouteView(
        Long id,
        String name,
        String departureCity,
        String destination,
        Integer durationDays,
        String description,
        String coverUrl,
        BigDecimal minAdultPrice,
        LocalDate nextDepartureDate,
        Integer availableSeats,
        BigDecimal ratingAvg,
        Integer ratingCount,
        Integer validBookingCount,
        String status,
        boolean favorite,
        String included,
        String excluded,
        String bookingNotice,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static RouteView from(TravelRoute route,
                                 BigDecimal minAdultPrice,
                                 LocalDate nextDepartureDate,
                                 Integer availableSeats,
                                 boolean favorite) {
        if (route == null) {
            return null;
        }
        return new RouteView(
                route.id,
                route.name,
                route.departureCity,
                route.destination,
                route.durationDays,
                route.description,
                route.coverUrl,
                minAdultPrice,
                nextDepartureDate,
                availableSeats,
                route.ratingAvg == null ? BigDecimal.ZERO : route.ratingAvg,
                route.ratingCount == null ? 0 : route.ratingCount,
                route.validBookingCount == null ? 0 : route.validBookingCount,
                route.status,
                favorite,
                route.included,
                route.excluded,
                route.bookingNotice,
                route.createdAt,
                route.updatedAt);
    }
}
