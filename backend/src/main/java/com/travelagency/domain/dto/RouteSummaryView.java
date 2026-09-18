package com.travelagency.domain.dto;

import com.travelagency.domain.entity.TravelRoute;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 线路摘要视图，对齐契约 RouteSummary（additionalProperties: false）。
 *
 * <p>不直接序列化持久化实体 {@link TravelRoute}：实体带 included / excluded / bookingNotice /
 * createdBy / deleted 等契约未列出的字段，直接输出既违反 additionalProperties 约束，
 * 又把「逻辑删除标记、创建人」这类内部字段暴露给调用方。</p>
 *
 * <p>本视图未填充的可选字段（minAdultPrice / nextDepartureDate / availableSeats）契约均允许为
 * null；契约中的 favorite 依赖当前登录用户，本视图不输出该字段。
 * ratingAvg / ratingCount / validBookingCount 是契约必填字段，数据库为空时按 0 兜底。</p>
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
        String status) {

    public static RouteSummaryView from(TravelRoute route) {
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
                route.minAdultPrice,
                null,
                null,
                route.ratingAvg == null ? BigDecimal.ZERO : route.ratingAvg,
                route.ratingCount == null ? 0 : route.ratingCount,
                route.validBookingCount == null ? 0 : route.validBookingCount,
                route.status);
    }
}
