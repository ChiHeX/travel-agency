package com.travelagency.domain.service;

import com.travelagency.domain.dto.UpcomingReminderTarget;
import com.travelagency.domain.entity.DepartureReminder;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.mapper.DepartureReminderMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 即将出发提醒（PRD §29）单元测试，全部依赖用 Mockito 替身，随普通 {@code mvn test} 执行。
 *
 * <p>钉住三条完成标准：提前天数作为查询窗口传入；每个订单只发一条消息（唯一键幂等）；
 * 关闭开关后定时任务不产生任何查询。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DepartureReminderServiceTest {

    private static final int UPCOMING_DAYS = 3;

    @Mock private TravelOrderMapper orderMapper;
    @Mock private DepartureReminderMapper reminderMapper;
    @Mock private MessageMapper messageMapper;

    private DepartureReminderService service;

    @BeforeEach
    void setUp() {
        service = new DepartureReminderService(orderMapper, reminderMapper, messageMapper, UPCOMING_DAYS, true);
    }

    @Test
    @DisplayName("提醒：按提前天数取候选订单，为每个订单发一条消息")
    void sendsOneMessagePerEligibleOrder() {
        when(orderMapper.selectUpcomingReminderTargets(UPCOMING_DAYS)).thenReturn(List.of(
                target(1L, "TA1", 11L),
                target(2L, "TA2", 12L)));
        eligible(1L, "TA1", 11L);
        eligible(2L, "TA2", 12L);

        int sent = service.sendUpcomingReminders();

        assertEquals(2, sent);
        verify(messageMapper, times(2)).insert(any(Message.class));
        verify(reminderMapper, times(2)).insert(any(DepartureReminder.class));
    }

    @Test
    @DisplayName("提醒：重复执行不重复发消息（已发送过则唯一键冲突、跳过）")
    void repeatedRunDoesNotSendAgain() {
        when(orderMapper.selectUpcomingReminderTargets(UPCOMING_DAYS)).thenReturn(List.of(target(1L, "TA1", 11L)));
        eligible(1L, "TA1", 11L);
        when(reminderMapper.insert(any(DepartureReminder.class)))
                .thenThrow(new DuplicateKeyException("uk_departure_reminder_order_type"));

        int sent = service.sendUpcomingReminders();

        assertEquals(0, sent);
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("提醒：发送前复核不通过（已退款/已取消/已改期出窗口）时跳过，且不留发送标记")
    void skipsWhenSendTimeRecheckFails() {
        when(orderMapper.selectUpcomingReminderTargets(UPCOMING_DAYS)).thenReturn(List.of(target(1L, "TA1", 11L)));
        // 复核用的是当前读，返回 null 表示这条候选在真正写入之前已经不符合出行条件。
        when(orderMapper.lockEligibleReminderOrder(1L, UPCOMING_DAYS)).thenReturn(null);

        int sent = service.sendUpcomingReminders();

        assertEquals(0, sent);
        verify(messageMapper, never()).insert(any(Message.class));
        verify(reminderMapper, never()).insert(any(DepartureReminder.class));
    }

    @Test
    @DisplayName("提醒：正文用复核返回的最新数据，不用候选快照里的旧数据（团期改期场景）")
    void messageUsesFreshDataFromTheRecheck() {
        LocalDate staleDate = LocalDate.now().plusDays(2);
        LocalDate freshDate = LocalDate.now().plusDays(3);
        when(orderMapper.selectUpcomingReminderTargets(UPCOMING_DAYS)).thenReturn(List.of(
                new UpcomingReminderTarget(1L, "TA1", 11L, staleDate, "云南 6 日")));
        // 复核返回的是改期后的新日期
        when(orderMapper.lockEligibleReminderOrder(1L, UPCOMING_DAYS)).thenReturn(
                new UpcomingReminderTarget(1L, "TA1", 11L, freshDate, "云南 6 日"));

        assertEquals(1, service.sendUpcomingReminders());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        assertTrue(captor.getValue().content.contains(freshDate.toString()),
                "正文必须写复核拿到的新出发日期，实际：" + captor.getValue().content);
        assertFalse(captor.getValue().content.contains(staleDate.toString()),
                "正文不得残留候选快照里的旧出发日期，实际：" + captor.getValue().content);
    }

    @Test
    @DisplayName("提醒：开关关闭时定时任务不查询、不发送")
    void disabledSchedulerDoesNothing() {
        new DepartureReminderService(orderMapper, reminderMapper, messageMapper, UPCOMING_DAYS, false)
                .scheduledUpcomingReminders();

        verifyNoInteractions(orderMapper, reminderMapper, messageMapper);
    }

    @Test
    @DisplayName("提醒：定时入口与手动入口走同一发送流程")
    void scheduledRunDeliversMessages() {
        when(orderMapper.selectUpcomingReminderTargets(UPCOMING_DAYS)).thenReturn(List.of(target(1L, "TA1", 11L)));
        eligible(1L, "TA1", 11L);

        service.scheduledUpcomingReminders();

        verify(messageMapper).insert(any(Message.class));
        verify(reminderMapper).insert(any(DepartureReminder.class));
    }

    /** 桩：发送前复核通过，并且返回该订单最新的提醒内容。 */
    private void eligible(Long orderId, String orderNo, Long userId) {
        when(orderMapper.lockEligibleReminderOrder(orderId, UPCOMING_DAYS))
                .thenReturn(target(orderId, orderNo, userId));
    }

    private static UpcomingReminderTarget target(Long orderId, String orderNo, Long userId) {
        return new UpcomingReminderTarget(orderId, orderNo, userId, LocalDate.now().plusDays(2), "云南 6 日");
    }
}
