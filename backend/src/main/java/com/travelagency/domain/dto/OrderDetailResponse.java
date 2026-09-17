package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelRoute;

import java.util.List;

/**
 * 订单详情，对齐契约 OrderDetail：
 * 下单快照 + 线路 + 团期 + 出行人快照 + 支付 + 退款列表 + 我的评价。
 * order 与 travelers 都使用视图对象，保证 routeName / travelerType 等契约必填字段存在。
 */
public record OrderDetailResponse(
        OrderView order,
        TravelRoute route,
        Departure departure,
        List<OrderTravelerView> travelers,
        PaymentView payment,
        List<RefundView> refunds,
        ReviewView review) {
}
