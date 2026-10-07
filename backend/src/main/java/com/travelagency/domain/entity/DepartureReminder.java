package com.travelagency.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.travelagency.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 出发提醒发送记录，对应 {@code departure_reminder}。
 *
 * <p>唯一键 (order_id, remind_type) 是幂等闸门：定时任务重复执行、或部署多实例并发触发时，
 * 同一订单的同一类提醒只有一条记录能插入成功，只有它对应的站内消息会被发出，
 * 从而保证「任务重复执行不重复发消息」。</p>
 */
@TableName("departure_reminder")
public class DepartureReminder extends BaseEntity {
    public Long orderId;
    /** 提醒类型，如 DEPARTURE_REMINDER（即将出发）。 */
    public String remindType;
    public LocalDateTime sentAt;
}
