package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;

import java.util.List;

/**
 * 订单详情，对齐契约 OrderDetail：
 * 下单快照 + 线路 + 团期 + 出行人快照 + 支付 + 退款列表 + 我的评价。
 */
public record OrderDetailResponse(
        TravelOrder order,
        TravelRoute route,
        Departure departure,
        List<TravelerView> travelers,
        PaymentView payment,
        List<RefundView> refunds,
        ReviewView review) {
}
