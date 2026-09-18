package com.travelagency.domain.dto;

import java.util.List;

/**
 * 导游工作台概览，对齐契约 {@code GuideDashboardData}：
 * { upcoming, current, history }，三个数组的元素都是契约 {@code Departure}。
 *
 * <p>替代此前返回 {@code Map} 且键名为 {@code active} 的实现：键名与契约不一致时，
 * 前端 {@code data.current} 取不到值，页面统计与列表会直接失效。</p>
 */
public record GuideDashboardView(
        List<DepartureView> upcoming,
        List<DepartureView> current,
        List<DepartureView> history) {
}
