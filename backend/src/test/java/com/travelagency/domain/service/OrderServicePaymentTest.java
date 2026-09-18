package com.travelagency.domain.service;

import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.exception.BusinessException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员 B 交易模块：支付回调幂等。
 * 支付宝会重复投递异步通知，重复处理绝不能把订单推进两次。
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

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderMapper, departureMapper, routeMapper, guideMapper,
                orderTravelerMapper, paymentMapper, refundMapper, reviewMapper, messageMapper, sysUserMapper,
                idempotencyRecordMapper);
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
}
