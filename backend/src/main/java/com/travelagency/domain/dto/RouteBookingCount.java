package com.travelagency.domain.dto;

/**
 * 「有效报名订单条数」排行的一行，供热门线路实时统计使用（PRD §27：热门线路按有效报名订单统计）。
 *
 * <p>由 {@code TravelRouteMapper#popularRouteCounts} 直接聚合 {@code travel_order} 得到：
 * {@code routeId} 是线路，{@code bookingCount} 是该线路下「有效报名」订单的条数。</p>
 *
 * <p>刻意不读 {@code travel_route.valid_booking_count} 这一物化计数列：该列容易被预置或被绕过
 * 业务链路的写入污染，用它排行会与真实订单不符。这里以订单为唯一事实来源。</p>
 */
public record RouteBookingCount(Long routeId, int bookingCount) {
}
