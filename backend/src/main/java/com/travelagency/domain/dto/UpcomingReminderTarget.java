package com.travelagency.domain.dto;

import java.time.LocalDate;

/**
 * 即将出发提醒的候选订单投影：一条记录的粒度是「一个订单」。
 *
 * <p>由 {@code TravelOrderMapper#selectUpcomingReminderTargets} 直接以 SQL 组装，
 * 日期口径在库里用 {@code CURRENT_DATE()} 判定，避免 JVM 与会话时区不一致时错开一天。</p>
 */
public record UpcomingReminderTarget(
        Long orderId,
        String orderNo,
        Long userId,
        LocalDate startDate,
        String routeName) {
}
