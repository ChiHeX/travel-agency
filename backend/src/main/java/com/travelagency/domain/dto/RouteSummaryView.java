package com.travelagency.domain.dto;

import com.travelagency.domain.entity.TravelRoute;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 线路列表项视图，对齐契约 {@code RouteSummary}（additionalProperties: false）。
 *
 * <p>不直接序列化 {@link TravelRoute} 实体：实体额外带 {@code createdBy} / {@code deleted}
 * 等契约未声明的字段，且缺少 {@code nextDepartureDate} / {@code availableSeats} 这类
 * 需要联查团期才能得到的计算字段；实体自带的 {@code minAdultPrice} 也需要按最低在售价格重新计算。</p>
 */
public record RouteSummaryView(
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
        boolean favorite) {

    /**
     * 不需要用户态收藏信息和团期聚合字段的场景使用该重载，例如订单详情中的线路快照展示。
     */
    public static RouteSummaryView from(TravelRoute route) {
        return from(route, route == null ? null : route.minAdultPrice, null, null, false);
    }

    public static RouteSummaryView from(TravelRoute route,
                                        BigDecimal minAdultPrice,
                                        LocalDate nextDepartureDate,
                                        Integer availableSeats,
                                        boolean favorite) {
        if (route == null) {
            return null;
        }
        return new RouteSummaryView(
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
                // 契约把 ratingAvg / ratingCount / validBookingCount 列为 required 且不可空，
                // 历史数据可能为 NULL，这里统一归零，避免返回 null 破坏契约。
                route.ratingAvg == null ? BigDecimal.ZERO : route.ratingAvg,
                route.ratingCount == null ? 0 : route.ratingCount,
                route.validBookingCount == null ? 0 : route.validBookingCount,
                route.status,
                favorite);
    }
}
