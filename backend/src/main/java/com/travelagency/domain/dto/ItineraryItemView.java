package com.travelagency.domain.dto;

import com.travelagency.domain.entity.RouteItineraryItem;

import java.math.BigDecimal;

/**
 * 行程项目视图，对齐契约 {@code ItineraryItem}（additionalProperties: false）。
 *
 * <p>不直接序列化实体：实体继承 {@code BaseEntity}，会额外带出契约未声明的
 * {@code createdAt} / {@code updatedAt} / {@code dayId}。</p>
 *
 * <p>经纬度使用 {@link Double} 而不是 {@link BigDecimal}：契约把 Longitude / Latitude
 * 定义为 JSON number，而全局序列化器会把 BigDecimal 输出成十进制字符串（那是金额的约定），
 * 直接透传会导致类型不符合契约。坐标按 7 位小数精度用 double 表达不会丢失有效信息。</p>
 */
public record ItineraryItemView(
        Long id,
        Integer sortNo,
        String itemType,
        String name,
        String description,
        Long attractionId,
        Double longitude,
        Double latitude) {

    public static ItineraryItemView from(RouteItineraryItem item) {
        if (item == null) {
            return null;
        }
        return new ItineraryItemView(
                item.id,
                item.sortNo,
                item.itemType,
                item.name,
                item.description,
                item.attractionId,
                toDouble(item.longitude),
                toDouble(item.latitude));
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
