package com.travelagency.domain.dto;

import java.util.List;

/**
 * 整条线路的详情视图，对齐契约 {@code RouteDetail}：
 * { route, departures, itinerary, reviews, favorite }。
 *
 * <p>后台线路管理详情使用，替代此前直接序列化实体与 {@code Map} 的临时结构。</p>
 */
public record RouteDetailView(
        RouteView route,
        List<DepartureView> departures,
        List<ItineraryDayView> itinerary,
        List<ReviewView> reviews,
        boolean favorite) {
}
