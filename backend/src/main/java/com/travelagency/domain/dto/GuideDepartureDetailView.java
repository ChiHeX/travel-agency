package com.travelagency.domain.dto;

import java.util.List;

/**
 * 导游端团期详情，对齐契约 {@code GuideDepartureDetail}：
 * { departure, route, itinerary }，additionalProperties 为 false。
 *
 * <p>替代此前用 {@code Map<String, Object>} 拼装、并用 {@code passengers} 顶替 {@code itinerary}
 * 的实现：导游通过该接口获取本人负责团期的每日行程，游客名单由
 * {@code GET /guide/departures/{departureId}/passengers} 单独提供。</p>
 */
public record GuideDepartureDetailView(
        DepartureView departure,
        RouteView route,
        List<ItineraryDayView> itinerary) {
}
