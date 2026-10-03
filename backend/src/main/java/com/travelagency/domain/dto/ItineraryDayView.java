package com.travelagency.domain.dto;

import java.util.List;

/**
 * 每日行程视图，对齐契约 {@code ItineraryDay}（additionalProperties: false）。
 *
 * <p>不直接序列化实体：实体继承 {@code BaseEntity} 会额外带出 {@code createdAt} /
 * {@code updatedAt}，且缺少契约声明的 {@code hotelName}、住宿安排字段与嵌套的 {@code items}。</p>
 *
 * <p>{@code hotelId} / {@code hotelName} 是当天安排的住宿（旧字段，保留不动）；
 * {@code accommodationType} 起说明当天到底怎么安排住宿，两者必须一致：
 * 只有 {@code HOTEL} 才允许 {@code hotelId} 非空。{@code hotelId} 为空<b>不等于</b>当天不含住宿
 * （可能是只确定了住宿标准，或还没确认），因此调用方不得用 {@code hotelId == null} 推断不含住宿。</p>
 *
 * <p>{@code hotel} 是酒店摘要：{@code hotelId} 为空、酒店已停用或已删除时为 {@code null}
 * （停用酒店的行程仍保留 {@code hotelId} / {@code hotelName}，只是不再提供公开详情，
 * 见 {@link HotelSummaryView#forItinerary}）。</p>
 *
 * <p>{@code breakfastIncluded} 只表示本线路当天住宿是否含早餐；酒店是否有早餐服务由
 * {@code Hotel.facilities} 的 {@code BREAKFAST_SERVICE} 表达，两者互不推断。</p>
 */
public record ItineraryDayView(
        Long id,
        Long routeId,
        Integer dayNumber,
        String title,
        String description,
        String transportation,
        String meals,
        String accommodationType,
        String accommodationStandard,
        String roomType,
        Boolean breakfastIncluded,
        String accommodationNote,
        Long hotelId,
        String hotelName,
        HotelSummaryView hotel,
        List<ItineraryItemView> items) {
}
