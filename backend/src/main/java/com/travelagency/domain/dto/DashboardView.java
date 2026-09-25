package com.travelagency.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 后台工作台视图，对齐契约 {@code DashboardData}（{@code additionalProperties: false}）。
 *
 * <p>计数字段一律用 {@code int} 而不是 {@code long}：全局 JacksonConfig 把 {@code Long}
 * 序列化成字符串（为了 ID 不被 JS 精度截断），而契约给这些字段声明的是 {@code integer}。
 * 2 位小数的金额仍用 {@link BigDecimal}，走同一条 {@code Money} 字符串口径。</p>
 */
public record DashboardView(
        int userCount,
        int publishedRouteCount,
        int openDepartureCount,
        int todayOrderCount,
        int pendingConfirmCount,
        int pendingRefundCount,
        int participantCount,
        BigDecimal grossOrderAmount,
        List<Metric> orderTrend,
        List<RouteSummaryView> popularRoutes,
        List<HomeView.Destination> popularDestinations) {

    /** 契约 {@code DashboardMetric}：按日聚合的一行。 */
    public record Metric(LocalDate date, int orderCount, int participantCount, BigDecimal orderAmount) {
    }
}
