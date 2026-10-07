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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 成员 C：即将出发提醒（PRD §29）单元测试，全部依赖用 Mockito 替身，随普通 {@code mvn test} 执行。
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

        int sent = service.sendUpcomingReminders();

        assertEquals(2, sent);
        verify(messageMapper, times(2)).insert(any(Message.class));
        verify(reminderMapper, times(2)).insert(any(DepartureReminder.class));
    }

    @Test
    @DisplayName("提醒：重复执行不重复发消息（已发送过则唯一键冲突、跳过）")
    void repeatedRunDoesNotSendAgain() {
        when(orderMapper.selectUpcomingReminderTargets(UPCOMING_DAYS)).thenReturn(List.of(target(1L, "TA1", 11L)));
        when(reminderMapper.insert(any(DepartureReminder.class)))
                .thenThrow(new DuplicateKeyException("uk_departure_reminder_order_type"));

        int sent = service.sendUpcomingReminders();

        assertEquals(0, sent);
        verify(messageMapper, never()).insert(any(Message.class));
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

        service.scheduledUpcomingReminders();

        verify(messageMapper).insert(any(Message.class));
        verify(reminderMapper).insert(any(DepartureReminder.class));
    }

    private static UpcomingReminderTarget target(Long orderId, String orderNo, Long userId) {
        return new UpcomingReminderTarget(orderId, orderNo, userId, LocalDate.now().plusDays(2), "云南 6 日");
    }
}
