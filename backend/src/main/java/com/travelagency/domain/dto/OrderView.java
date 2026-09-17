package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单个订单，对齐契约 Order（= OrderSummary + 价格/联系邮箱/备注/各时间点）。
 * 用于 POST /orders 的创建响应与 OrderDetail.order。
 */
public record OrderView(
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
        String contactEmail,
        Integer adultCount,
        Integer childCount,
        BigDecimal adultUnitPrice,
        BigDecimal childUnitPrice,
        BigDecimal totalAmount,
        String status,
        String paymentStatus,
        LocalDateTime paidAt,
        LocalDateTime confirmedAt,
        LocalDateTime cancelledAt,
        LocalDateTime completedAt,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static OrderView from(TravelOrder order, TravelRoute route, Departure departure) {
        return new OrderView(
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
                order.contactEmail,
                order.adultCount,
                order.childCount,
                order.adultUnitPrice,
                order.childUnitPrice,
                order.totalAmount,
                order.status,
                order.paymentStatus,
                order.paidAt,
                order.confirmedAt,
                order.cancelledAt,
                order.completedAt,
                order.remark,
                order.createdAt,
                order.updatedAt);
    }
}
