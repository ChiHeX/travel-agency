package com.travelagency.domain.service;

import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.PaymentStartResponse;
import com.travelagency.domain.entity.IdempotencyRecord;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.Payment;
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
import com.travelagency.domain.mapper.TravelerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员 B 交易模块：支付回调幂等与发起支付的幂等键。
 * 支付宝会重复投递异步通知，重复处理绝不能把订单推进两次；
 * 同一幂等键重试 {@code POST /orders/{orderNo}/pay} 也只能真正发起一次支付。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServicePaymentTest {

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
    @Mock
    private TravelerMapper travelerMapper;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderMapper, departureMapper, routeMapper, guideMapper,
                orderTravelerMapper, paymentMapper, refundMapper, reviewMapper, messageMapper, sysUserMapper,
                idempotencyRecordMapper, travelerMapper);
    }

    private static TravelOrder order(long id, String status, String paymentStatus) {
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
        o.paymentStatus = paymentStatus;
        return o;
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

    // ---------------------------------------------------------------- 正常路径

    @Test
    @DisplayName("回调成功：订单转 PAID_WAIT_CONFIRM，支付单转 PAID 并记录交易号")
    void markPaidTransitionsOrder() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.PENDING);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);
        // 条件更新抢到「非 PAID → PAID」的一次转换
        when(paymentMapper.update(any(), any())).thenReturn(1);

        orderService.markPaid(o.orderNo, "ALI-TRADE-0001");

        assertEquals(PaymentStatus.PAID, p.status);
        assertEquals("ALI-TRADE-0001", p.thirdPartyTradeNo);
        assertNotNull(p.paidAt);
        assertEquals(OrderStatus.PAID_WAIT_CONFIRM, o.status);
        assertEquals(PaymentStatus.PAID, o.paymentStatus);
        assertNotNull(o.paidAt);

        verify(paymentMapper).updateById(p);
        verify(orderMapper).updateById(o);
        // 通知用户支付成功
        verify(messageMapper).insert(any(Message.class));
    }

    // ---------------------------------------------------------------- 幂等

    @Test
    @DisplayName("幂等：支付单已是 PAID 时重复回调不做任何二次推进")
    void markPaidIsIdempotentWhenAlreadyPaid() {
        TravelOrder o = order(55L, OrderStatus.PAID_WAIT_CONFIRM, PaymentStatus.PAID);
        Payment p = payment(55L, PaymentStatus.PAID);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);

        orderService.markPaid(o.orderNo, "ALI-TRADE-0001");
        orderService.markPaid(o.orderNo, "ALI-TRADE-0001");

        // 订单与支付单都不得被再次更新，也不能重复发通知
        verify(paymentMapper, never()).updateById(any(Payment.class));
        verify(orderMapper, never()).updateById(any(TravelOrder.class));
        verify(messageMapper, never()).insert(any(Message.class));
        assertEquals(OrderStatus.PAID_WAIT_CONFIRM, o.status);
    }

    @Test
    @DisplayName("幂等：并发重复投递时，没抢到原子闸门的回调不再推进订单")
    void markPaidSkipsWhenConcurrentCallbackWonTheClaim() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.PENDING);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);
        // 条件更新影响 0 行 = 另一个并发回调已经先完成了转换
        when(paymentMapper.update(any(), any())).thenReturn(0);

        orderService.markPaid(o.orderNo, "ALI-TRADE-0001");

        verify(orderMapper, never()).updateById(any(TravelOrder.class));
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("金额核对：回调金额与订单应付金额不一致时拒绝入账")
    void markPaidRejectsAmountMismatch() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.PENDING);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.markPaid(o.orderNo, "ALI-TRADE-0001", new BigDecimal("0.01")));

        assertEquals(409, ex.getStatus());
        assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.getCode());
        verify(orderMapper, never()).updateById(any(TravelOrder.class));
        verify(messageMapper, never()).insert(any(Message.class));
    }

    // ---------------------------------------------------------------- 状态冲突

    @Test
    @DisplayName("已取消订单不接受支付回调")
    void markPaidRejectsCancelledOrder() {
        TravelOrder o = order(55L, OrderStatus.CANCELLED, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.PENDING);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.markPaid(o.orderNo, "ALI-TRADE-0001"));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        verify(orderMapper, never()).updateById(any(TravelOrder.class));
    }

    @Test
    @DisplayName("订单不存在时报 404")
    void markPaidRejectsUnknownOrder() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.markPaid("TA-NOT-EXIST", "ALI-TRADE-0001"));

        assertEquals(404, ex.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", ex.getCode());
    }

    // ------------------------------------------------- 发起支付的幂等键（Idempotency-Key）

    /** 构造一条幂等记录；resourceNo 为 null 表示首个请求还没回填业务单号。 */
    private static IdempotencyRecord idempotency(String resourceNo, LocalDateTime createdAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = 501L;
        record.userId = 9L;
        record.scope = "START_PAYMENT";
        record.idemKey = "IDEM-PAY-0001";
        record.resourceType = resourceNo == null ? null : "PAYMENT";
        record.resourceNo = resourceNo;
        record.createdAt = createdAt;
        return record;
    }

    /** 让「重放」分支生效：同一幂等键再次插入会撞唯一键。 */
    private void givenKeyAlreadyClaimed(IdempotencyRecord existing) {
        when(idempotencyRecordMapper.insert(any(IdempotencyRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_idempotency_user_scope_key"));
        when(idempotencyRecordMapper.selectOne(any())).thenReturn(existing);
    }

    @Test
    @DisplayName("幂等：同一幂等键重复发起支付只返回同一个支付单，且不改写支付单、不滑动有效期")
    void startPaymentReplaysSamePaymentWithoutTouchingTheRow() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.UNPAID);
        LocalDateTime anchor = LocalDateTime.now().minusMinutes(5);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.selectById(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);
        // 首次：抢占成功（insert 返回 1），随后回读拿到窗口锚点
        when(idempotencyRecordMapper.insert(any(IdempotencyRecord.class))).thenReturn(1);
        when(idempotencyRecordMapper.selectOne(any())).thenReturn(idempotency(null, anchor));

        PaymentStartResponse first = orderService.startPayment(o.orderNo, o.userId, "IDEM-PAY-0001");

        givenKeyAlreadyClaimed(idempotency(p.paymentNo, anchor));
        PaymentStartResponse replay = orderService.startPayment(o.orderNo, o.userId, "IDEM-PAY-0001");

        assertEquals(first.paymentNo(), replay.paymentNo());
        // 有效期锚定在首次抢占幂等记录的时刻：重试不会把支付窗口一次次往后顺延
        assertEquals(anchor.plusMinutes(30), first.expiresAt());
        assertEquals(first.expiresAt(), replay.expiresAt());
        // 支付单只在首次被改写一次，重放没有二次副作用
        verify(paymentMapper, times(1)).updateById(p);
    }

    @Test
    @DisplayName("幂等：订单已支付后用同一键重试，返回首次的支付单而不是 409")
    void startPaymentReplayWinsOverStateCheck() {
        // 订单已推进到 PAID_WAIT_CONFIRM；同一幂等键的重试属于原请求的重放，应拿到首次结果
        TravelOrder o = order(55L, OrderStatus.PAID_WAIT_CONFIRM, PaymentStatus.PAID);
        Payment p = payment(55L, PaymentStatus.PAID);
        givenKeyAlreadyClaimed(idempotency(p.paymentNo, LocalDateTime.now().minusMinutes(5)));
        when(paymentMapper.selectOne(any())).thenReturn(p);
        when(orderMapper.selectById(any())).thenReturn(o);

        PaymentStartResponse replay = orderService.startPayment(o.orderNo, o.userId, "IDEM-PAY-0001");

        assertEquals(p.paymentNo, replay.paymentNo());
        assertEquals(OrderStatus.PAID_WAIT_CONFIRM, o.status);
        verify(paymentMapper, never()).updateById(any(Payment.class));
    }

    @Test
    @DisplayName("幂等：首个请求仍在处理中（支付单号未回填）时报 409，而不是给出空的支付信息")
    void startPaymentReplayInProgressIsRejected() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        givenKeyAlreadyClaimed(idempotency(null, LocalDateTime.now()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.startPayment(o.orderNo, o.userId, "IDEM-PAY-0001"));

        assertEquals(409, ex.getStatus());
        assertEquals("IDEMPOTENT_REQUEST_IN_PROGRESS", ex.getCode());
        verify(paymentMapper, never()).updateById(any(Payment.class));
    }

    @Test
    @DisplayName("幂等键绑定到订单：同一个键用到另一张订单上直接拒绝")
    void startPaymentRejectsKeyReusedOnAnotherOrder() {
        TravelOrder recorded = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.PENDING);
        recorded.orderNo = "TA20270301000002ZZZZ9999";
        Payment p = payment(55L, PaymentStatus.PENDING);
        givenKeyAlreadyClaimed(idempotency(p.paymentNo, LocalDateTime.now()));
        when(paymentMapper.selectOne(any())).thenReturn(p);
        when(orderMapper.selectById(any())).thenReturn(recorded);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.startPayment("TA20270301000001ABCD1234", 9L, "IDEM-PAY-0001"));

        assertEquals(409, ex.getStatus());
        assertEquals("IDEMPOTENCY_KEY_REUSED", ex.getCode());
        verify(paymentMapper, never()).updateById(any(Payment.class));
    }

    @Test
    @DisplayName("不带幂等键时保持原行为：改支付单为 PENDING 并按当前时刻给 30 分钟窗口")
    void startPaymentWithoutKeyKeepsLegacyBehaviour() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.UNPAID);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);

        PaymentStartResponse response = orderService.startPayment(o.orderNo, o.userId);

        assertEquals(PaymentStatus.PENDING, p.status);
        assertEquals(p.paymentNo, response.paymentNo());
        assertEquals(o.totalAmount, response.amount());
        assertTrue(response.expiresAt().isAfter(LocalDateTime.now().plusMinutes(29)));
        verify(paymentMapper).updateById(p);
        verify(idempotencyRecordMapper, never()).insert(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("状态闸门没有被幂等放宽：不带幂等键时非待支付订单仍报 409")
    void startPaymentRejectsNonWaitPayOrderWithoutKey() {
        TravelOrder o = order(55L, OrderStatus.CONFIRMED, PaymentStatus.PAID);
        Payment p = payment(55L, PaymentStatus.PAID);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.startPayment(o.orderNo, o.userId));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        verify(paymentMapper, never()).updateById(any(Payment.class));
    }
}
