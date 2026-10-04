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
        List<HomeView.Destination> popularDestinations,
        List<Enrollment> departureEnrollment) {

    /** 契约 {@code DashboardMetric}：按日聚合的一行。 */
    public record Metric(LocalDate date, int orderCount, int participantCount, BigDecimal orderAmount) {
    }

    /**
     * 契约 {@code DepartureEnrollment}：一个尚未出发、仍在销售中的团期的报名情况。
     *
     * <p>{@code departureId} / {@code routeId} 用 {@code Long}：契约把它们声明为 {@code Id}（字符串），
     * 全局 JacksonConfig 正好把 {@code Long} 序列化成字符串；名额相关的计数字段反过来必须是
     * {@code int}，否则会被序列化成契约不接受的字符串（见类注释）。</p>
     */
    public record Enrollment(
            Long departureId,
            Long routeId,
            String routeName,
            LocalDate startDate,
            int maxPeople,
            int reservedPeople,
            int confirmedPeople,
            int remainingSeats) {
    }
}
