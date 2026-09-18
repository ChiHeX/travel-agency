package com.travelagency.domain.dto;

import java.util.List;

/**
 * 订单详情，对齐契约 OrderDetail：
 * 下单快照 + 线路 + 团期 + 出行人快照 + 支付 + 退款列表 + 我的评价。
 *
 * <p>order / route / departure 全部使用契约视图对象，不直接暴露持久化实体：
 * <ul>
 *   <li>{@code TravelRoute} 直出会多出 included / excluded / notice / createdBy / deleted，违反 additionalProperties: false；</li>
 *   <li>{@code Departure} 直出会多出 version，且缺少契约必填的 availableSeats 与 routeName / guideName。</li>
 * </ul>
 */
public record OrderDetailResponse(
        OrderView order,
        RouteSummaryView route,
        DepartureView departure,
        List<OrderTravelerView> travelers,
        PaymentView payment,
        List<RefundView> refunds,
        ReviewView review) {
}
