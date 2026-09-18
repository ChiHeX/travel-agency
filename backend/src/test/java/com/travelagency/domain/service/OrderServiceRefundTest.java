package com.travelagency.domain.service;

import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.enums.RefundStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.RefundRequest;
import com.travelagency.domain.dto.RefundView;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.Payment;
import com.travelagency.domain.entity.Refund;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.IdempotencyRecordMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.OrderTravelerMapper;
import com.travelagency.domain.mapper.PaymentMapper;
import com.travelagency.domain.mapper.RefundMapper;
import com.travelagency.domain.mapper.ReviewMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员 B 交易模块：退款状态机与名额回滚。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceRefundTest {

    @Mock
    private TravelOrderMapper orderMapper;
    @Mock
    private DepartureMapper departureMapper;
    @Mock
    private TravelRouteMapper routeMapper;
    @Mock
    private GuideMapper guideMapper;
    @Mock
    private OrderTravelerMapper orderTravelerMapper;
    @Mock
    private PaymentMapper paymentMapper;
    @Mock
    private RefundMapper refundMapper;
    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private IdempotencyRecordMapper idempotencyRecordMapper;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderMapper, departureMapper, routeMapper, guideMapper,
                orderTravelerMapper, paymentMapper, refundMapper, reviewMapper, messageMapper, sysUserMapper,
                idempotencyRecordMapper);
    }

    private static TravelOrder order(long id, String status, String originalStatus) {
        TravelOrder o = new TravelOrder();
        o.id = id;
        o.orderNo = "TA20270301000001ABCD1234";
        o.userId = 9L;
        o.routeId = 100L;
        o.departureId = 7L;
        o.adultCount = 2;
        o.childCount = 1;
        o.totalAmount = new BigDecimal("2500.00");
        o.status = status;
        o.paymentStatus = PaymentStatus.PAID;
        return o;
    }

    private static Refund refund(long id, long orderId, String status, String originalStatus) {
        Refund r = new Refund();
        r.id = id;
        r.orderId = orderId;
        r.userId = 9L;
        r.amount = new BigDecimal("2500.00");
        r.reason = "行程有变";
        r.originalOrderStatus = originalStatus;
        r.status = status;
        return r;
    }

    private static Payment payment(long orderId, String status) {
        Payment p = new Payment();
        p.id = 33L;
        p.orderId = orderId;
        p.paymentNo = "PAYTA20270301000001ABCD1234";
        p.channel = "ALIPAY_SANDBOX";
        p.amount = new BigDecimal("2500.00");
        p.status = status;
        return p;
    }

    // ---------------------------------------------------------------- 审核通过

    @Test
    @DisplayName("审核通过：退款单转 REFUNDED、订单转 REFUNDED、支付单转 REFUNDED，并释放已确认名额")
    void approveSettlesOrderAndReleasesSeats() {
        Refund r = refund(70L, 55L, RefundStatus.APPLYING, OrderStatus.CONFIRMED);
        TravelOrder o = order(55L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        Payment p = payment(55L, PaymentStatus.PAID);
        when(refundMapper.selectById(70L)).thenReturn(r);
        when(orderMapper.selectById(55L)).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);
        // 抢占 APPLYING → PROCESSING 成功
        when(refundMapper.update(any(), any())).thenReturn(1);

        orderService.processRefund(70L, "APPROVE", "同意退款", 1L);

        assertEquals(RefundStatus.REFUNDED, r.status);
        assertEquals(1L, r.reviewedBy);
        assertEquals("同意退款", r.reviewComment);
        assertEquals(OrderStatus.REFUNDED, o.status);
        assertEquals(PaymentStatus.REFUNDED, o.paymentStatus);
        assertEquals(PaymentStatus.REFUNDED, p.status);

        // 先落 PROCESSING 再落 REFUNDED
        verify(refundMapper, times(2)).updateById(r);
        // 释放名额（原状态为 CONFIRMED → 减少 confirmed_people）
        verify(departureMapper).update(any(), any());
        // CONFIRMED 订单退款要回退线路的有效报名数
        verify(routeMapper).update(any(), any());
        verify(messageMapper).insert(any(Message.class));
    }

    @Test
    @DisplayName("审核通过：原状态为待确认时退还的是预留名额")
    void approveOnWaitConfirmReleasesReservedSeats() {
        Refund r = refund(71L, 56L, RefundStatus.APPLYING, OrderStatus.PAID_WAIT_CONFIRM);
        TravelOrder o = order(56L, OrderStatus.REFUND_APPLYING, OrderStatus.PAID_WAIT_CONFIRM);
        when(refundMapper.selectById(71L)).thenReturn(r);
        when(orderMapper.selectById(56L)).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(payment(56L, PaymentStatus.PAID));
        when(refundMapper.update(any(), any())).thenReturn(1);

        orderService.processRefund(71L, "APPROVE", "同意", 1L);

        assertEquals(RefundStatus.REFUNDED, r.status);
        verify(departureMapper).update(any(), any());
        // 未确认的订单没有占用 valid_booking_count，不应回退
        verify(routeMapper, never()).update(any(), any());
    }

    // ---------------------------------------------------------------- 审核拒绝

    @Test
    @DisplayName("审核拒绝：订单回到申请前的状态，且不释放名额")
    void rejectRestoresOriginalOrderStatus() {
        Refund r = refund(72L, 57L, RefundStatus.APPLYING, OrderStatus.CONFIRMED);
        TravelOrder o = order(57L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(72L)).thenReturn(r);
        when(orderMapper.selectById(57L)).thenReturn(o);
        when(refundMapper.update(any(), any())).thenReturn(1);

        orderService.processRefund(72L, "REJECT", "不符合退款条件", 1L);

        assertEquals(RefundStatus.REJECTED, r.status);
        assertEquals(OrderStatus.CONFIRMED, o.status);
        verify(departureMapper, never()).update(any(), any());
        verify(paymentMapper, never()).updateById(any(Payment.class));
        verify(messageMapper).insert(any(Message.class));
    }

    // ---------------------------------------------------------------- 状态机守卫

    @Test
    @DisplayName("退款单不存在时拒绝处理")
    void rejectsMissingRefund() {
        when(refundMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.processRefund(99L, "APPROVE", "同意", 1L));

        assertEquals(409, ex.getStatus());
        assertEquals("REFUND_STATE_CONFLICT", ex.getCode());
    }

    @Test
    @DisplayName("已处理过的退款单不能重复审核（防重复退款）")
    void rejectsDuplicateProcessing() {
        when(refundMapper.selectById(73L))
                .thenReturn(refund(73L, 58L, RefundStatus.REFUNDED, OrderStatus.CONFIRMED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.processRefund(73L, "APPROVE", "同意", 1L));

        assertEquals(409, ex.getStatus());
        assertEquals("REFUND_STATE_CONFLICT", ex.getCode());
        verify(orderMapper, never()).updateById(any(TravelOrder.class));
    }

    @Test
    @DisplayName("并发审批：没抢到 APPLYING→PROCESSING 的请求被拒绝，名额只释放一次")
    void rejectsConcurrentApproveThatLosesTheClaim() {
        Refund r = refund(76L, 60L, RefundStatus.APPLYING, OrderStatus.CONFIRMED);
        TravelOrder o = order(60L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(76L)).thenReturn(r);
        when(orderMapper.selectById(60L)).thenReturn(o);
        // 条件更新影响 0 行 = 另一个审核人已经先完成了状态抢占
        when(refundMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.processRefund(76L, "APPROVE", "同意", 1L));

        assertEquals(409, ex.getStatus());
        assertEquals("REFUND_STATE_CONFLICT", ex.getCode());
        // 关键：名额与线路有效报名数都不得被释放
        verify(departureMapper, never()).update(any(), any());
        verify(routeMapper, never()).update(any(), any());
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("非法审核动作被拒绝")
    void rejectsInvalidAction() {
        when(refundMapper.selectById(74L))
                .thenReturn(refund(74L, 59L, RefundStatus.APPLYING, OrderStatus.CONFIRMED));
        when(orderMapper.selectById(59L)).thenReturn(order(59L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.processRefund(74L, "MAYBE", "再看看", 1L));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }

    @Test
    @DisplayName("拒绝退款必须填写审核意见")
    void rejectRequiresComment() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.rejectRefund(75L, "   ", 1L));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }

    @Test
    @DisplayName("拒绝退款：返回契约 RefundEnvelope 所需的退款视图，状态为 REJECTED")
    void rejectReturnsUpdatedRefundView() {
        Refund r = refund(77L, 61L, RefundStatus.APPLYING, OrderStatus.CONFIRMED);
        TravelOrder o = order(61L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(77L)).thenReturn(r);
        when(orderMapper.selectById(61L)).thenReturn(o);
        when(refundMapper.update(any(), any())).thenReturn(1);

        RefundView view = orderService.rejectRefund(77L, "材料不齐，请补充", 1L);

        assertNotNull(view, "契约要求 data 为退款对象，不能为 null");
        assertEquals(r.id, view.id());
        assertEquals(RefundStatus.REJECTED, view.status());
        assertEquals(o.orderNo, view.orderNo());
        assertEquals("材料不齐，请补充", view.reviewComment());
        assertEquals(1L, view.reviewedBy());
        assertNotNull(view.reviewedAt());
        // 拒绝不释放名额、不动支付单
        verify(departureMapper, never()).update(any(), any());
        verify(paymentMapper, never()).updateById(any(Payment.class));
    }

    // ---------------------------------------------------------------- 申请退款

    @Test
    @DisplayName("申请退款：订单转 REFUND_APPLYING 并生成申请单")
    void applyRefundMarksOrderAsApplying() {
        TravelOrder o = order(60L, OrderStatus.PAID_WAIT_CONFIRM, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(refundMapper.selectOne(any())).thenReturn(null);
        AtomicReference<Refund> saved = new AtomicReference<>();
        when(refundMapper.insert(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            r.id = 80L;
            saved.set(r);
            return 1;
        });
        when(refundMapper.selectById(80L)).thenAnswer(inv -> saved.get());

        RefundView view = orderService.applyRefund(o.orderNo, 9L, new RefundRequest("行程有变"));

        assertEquals(RefundStatus.APPLYING, view.status());
        assertEquals(OrderStatus.REFUND_APPLYING, o.status);
        assertEquals(new BigDecimal("2500.00"), view.amount());
        assertEquals(o.orderNo, view.orderNo());
    }

    @Test
    @DisplayName("未支付订单不能申请退款")
    void applyRefundRejectsUnpaidOrder() {
        TravelOrder o = order(61L, OrderStatus.WAIT_PAY, OrderStatus.WAIT_PAY);
        when(orderMapper.selectOne(any())).thenReturn(o);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(o.orderNo, 9L, new RefundRequest("不想要了")));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        verify(refundMapper, never()).insert(any(Refund.class));
    }

    @Test
    @DisplayName("同一订单不能重复提交退款申请")
    void applyRefundRejectsDuplicateApplication() {
        TravelOrder o = order(62L, OrderStatus.PAID_WAIT_CONFIRM, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(refundMapper.selectOne(any()))
                .thenReturn(refund(81L, 62L, RefundStatus.APPLYING, OrderStatus.PAID_WAIT_CONFIRM));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(o.orderNo, 9L, new RefundRequest("再申请一次")));

        assertEquals(409, ex.getStatus());
        assertEquals("REFUND_ALREADY_APPLYING", ex.getCode());
        verify(refundMapper, never()).insert(any(Refund.class));
    }

    @Test
    @DisplayName("非订单所有者不能申请退款")
    void applyRefundRejectsNonOwner() {
        TravelOrder o = order(63L, OrderStatus.PAID_WAIT_CONFIRM, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(o.orderNo, 999L, new RefundRequest("越权申请")));

        assertEquals(403, ex.getStatus());
        assertEquals("ACCESS_DENIED", ex.getCode());
    }
}
