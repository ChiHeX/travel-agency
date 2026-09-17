package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单列表项，对齐契约 OrderSummary。
 * 契约要求返回 routeName，而 travel_order 只保存 routeId，因此必须以视图对象补充线路与团期信息，
 * 不能直接把 TravelOrder 实体当响应返回。
 */
public record OrderSummaryView(
        Long id,
        String orderNo,
        Long userId,
        Long routeId,
        Long departureId,
        String routeName,
        String routeCoverUrl,
        LocalDate departureStartDate,
        String contactName,
        String contactPhone,
        Integer adultCount,
        Integer childCount,
        BigDecimal totalAmount,
        String status,
        String paymentStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static OrderSummaryView from(TravelOrder order, TravelRoute route, Departure departure) {
        return new OrderSummaryView(
                order.id,
                order.orderNo,
                order.userId,
                order.routeId,
                order.departureId,
                route == null ? null : route.name,
                route == null ? null : route.coverUrl,
                departure == null ? null : departure.startDate,
                order.contactName,
                order.contactPhone,
                order.adultCount,
                order.childCount,
                order.totalAmount,
                order.status,
                order.paymentStatus,
                order.createdAt,
                order.updatedAt);
    }
}
