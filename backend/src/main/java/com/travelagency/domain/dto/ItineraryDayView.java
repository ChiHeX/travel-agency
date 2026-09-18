package com.travelagency.domain.dto;

import java.util.List;

/**
 * 每日行程视图，对齐契约 {@code ItineraryDay}（additionalProperties: false）。
 *
 * <p>不直接序列化实体：实体继承 {@code BaseEntity} 会额外带出 {@code createdAt} /
 * {@code updatedAt}，且缺少契约声明的 {@code hotelName} 与嵌套的 {@code items}。</p>
 */
public record ItineraryDayView(
        Long id,
        Long routeId,
        Integer dayNumber,
        String title,
        String description,
        String transportation,
        String meals,
        Long hotelId,
        String hotelName,
        List<ItineraryItemView> items) {
}
