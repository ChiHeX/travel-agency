package com.travelagency.domain.service;

import com.travelagency.domain.dto.UpcomingReminderTarget;
import com.travelagency.domain.entity.DepartureReminder;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.mapper.DepartureReminderMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 即将出发提醒（PRD §29：「即将出发」站内消息）。
 *
 * <p>定时任务在团期出发前固定天数内，为「符合出行条件」的订单各发一条站内消息。
 * 三个硬性约束都在这里落实：</p>
 * <ol>
 *   <li><b>提前多久提醒</b>：由 {@code app.reminder.upcoming-days} 配置，默认提前 3 天；
 *       任务在 {@code [今天, 今天 + N 天]} 这个窗口内触发，窗口在库内用 {@code CURRENT_DATE()} 判定，
 *       因此 JVM 与数据库时区不一致时"当天"仍然一致；</li>
 *   <li><b>只通知符合出行条件的订单</b>：候选订单限定为 {@code CONFIRMED}（已确认报名）且团期
 *       未取消、未完成；取消（{@code CANCELLED}）与退款完成（{@code REFUNDED}）的订单天然不在其中。
 *       候选查询读的是事务快照，因此发送前还会再做一次<b>当前读复核并加锁</b>
 *       （{@link TravelOrderMapper#lockEligibleReminderOrder}），避免"查出来之后才退款/取消"
 *       的订单收到提醒；</li>
 *   <li><b>重复执行不重复发消息</b>：每条提醒先向 {@code departure_reminder} 抢占
 *       唯一键 (order_id, remind_type)，只有抢占成功的那一次才写入站内消息。任务被重复调度、
 *       或多实例并发执行时，后到者读到唯一键冲突直接跳过。</li>
 * </ol>
 *
 * <p>整个发送过程在一个事务里完成：抢占记录与消息要么一起提交、要么一起回滚，
 * 不会出现"占了唯一键但消息没发出去"从而永久漏发的情况。单次调度若失败，
 * 由 Spring 调度器记录异常，下一次调度自然重试（唯一键保证不会重复发消息）。</p>
 */
@Service
public class DepartureReminderService {

    private static final Logger log = LoggerFactory.getLogger(DepartureReminderService.class);

    /** 即将出发提醒类型，与 {@code departure_reminder.remind_type} 对应。 */
    public static final String TYPE_UPCOMING = "DEPARTURE_REMINDER";

    private final TravelOrderMapper orderMapper;
    private final DepartureReminderMapper reminderMapper;
    private final MessageMapper messageMapper;

    /** 提前提醒天数：距出发日期还有不超过该天数的订单会被提醒。 */
    private final int upcomingDays;
    /** 是否启用即将出发提醒，默认启用；测试或特殊部署可关闭。 */
    private final boolean enabled;

    public DepartureReminderService(
            TravelOrderMapper orderMapper,
            DepartureReminderMapper reminderMapper,
            MessageMapper messageMapper,
            @Value("${app.reminder.upcoming-days:3}") int upcomingDays,
            @Value("${app.reminder.upcoming-enabled:true}") boolean enabled) {
        this.orderMapper = orderMapper;
        this.reminderMapper = reminderMapper;
        this.messageMapper = messageMapper;
        this.upcomingDays = upcomingDays;
        this.enabled = enabled;
    }

    /**
     * 定时入口：每天默认 09:00 执行一次（cron 可由 {@code app.reminder.upcoming-cron} 覆盖）。
     *
     * <p>方法自身是事务边界，由调度器通过代理调用，因此 {@link #deliverUpcoming()} 的写入
     * 处于同一事务中。开关关闭时直接返回，不产生任何数据库访问。</p>
     */
    @Scheduled(cron = "${app.reminder.upcoming-cron:0 0 9 * * *}")
    @Transactional
    public void scheduledUpcomingReminders() {
        if (!enabled) {
            return;
        }
        int sent = deliverUpcoming();
        if (sent > 0) {
            log.info("即将出发提醒已发送 {} 条", sent);
        }
    }

    /**
     * 立即执行一次即将出发提醒，返回本次新发送的消息条数。
     *
     * <p>方法可安全重复调用：已发送过的订单会被唯一键挡下，返回 0。供测试与手动触发使用。</p>
     */
    @Transactional
    public int sendUpcomingReminders() {
        return deliverUpcoming();
    }

    /**
     * 提醒发送主流程：取候选订单 → 逐单复核并锁定 → 抢占唯一键 → 写站内消息。
     *
     * <p>候选查询读的是事务快照，只有"查出候选"这一个动作并不足以证明此刻仍该发送，
     * 因此每一条候选在写入前都要经过 {@link TravelOrderMapper#lockEligibleReminderOrder}
     * 的当前读复核（见 {@link #deliverUpcoming} 的说明）。</p>
     */
    private int deliverUpcoming() {
        List<UpcomingReminderTarget> targets = orderMapper.selectUpcomingReminderTargets(upcomingDays);
        int sent = 0;
        for (UpcomingReminderTarget target : targets) {
            // 复核 + 加锁：候选快照可能已经过期（这期间退款完成、团期被取消），
            // 复核不通过就跳过，且不写"已发送"标记 —— 条件重新成立时下个周期仍可发送。
            // 这一步同时把订单与团期行锁到本事务结束，后续抢占与写消息不会再被并发状态变更插队。
            if (orderMapper.lockEligibleReminderOrder(target.orderId(), upcomingDays) == null) {
                log.info("订单 {} 在发送前复核时已不符合出行条件，跳过即将出发提醒", target.orderNo());
                continue;
            }
            if (!claim(target.orderId())) {
                continue;
            }
            messageMapper.insert(reminderMessage(target));
            sent++;
        }
        return sent;
    }

    /**
     * 抢占"该订单该类提醒已发送"的唯一标记。
     *
     * <p>返回 {@code true} 表示本次抢占成功、应发送消息；返回 {@code false} 表示已发送过。
     * 与 {@code OrderService} 的幂等抢占同构：并发下唯一键 (order_id, remind_type) 会阻塞后到的插入，
     * 待首个事务提交后抛重复键冲突，因此能可靠区分"首次"与"重复"。</p>
     */
    private boolean claim(Long orderId) {
        DepartureReminder record = new DepartureReminder();
        record.orderId = orderId;
        record.remindType = TYPE_UPCOMING;
        record.sentAt = LocalDateTime.now();
        try {
            reminderMapper.insert(record);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private static Message reminderMessage(UpcomingReminderTarget target) {
        Message message = new Message();
        message.userId = target.userId();
        message.type = TYPE_UPCOMING;
        message.title = "即将出发提醒";
        message.content = "订单 " + target.orderNo() + " 对应的「" + target.routeName()
                + "」团期将于 " + target.startDate() + " 出发，请提前核对行程与证件。";
        message.readFlag = 0;
        return message;
    }
}
