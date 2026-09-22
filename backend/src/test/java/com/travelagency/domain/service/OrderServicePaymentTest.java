package com.travelagency.domain.service;

import com.travelagency.common.alipay.AlipayGatewayClient;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.PaymentStartResponse;
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

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

    /** 适配器产出的真实沙箱收银台链接（测试里用桩值，断言的是「有没有走适配器」而非链接内容）。 */
    private static final String CASHIER_URL =
            "https://openapi-sandbox.dl.alipaydev.com/gateway.do?method=alipay.trade.page.pay&sign=stub";

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
    /** 支付宝沙箱适配器：仅在生成收银台地址时用到，测试中给默认 mock（未配置 → 回退占位地址）。 */
    @Mock
    private AlipayGatewayClient alipayGatewayClient;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderMapper, departureMapper, routeMapper, guideMapper,
                orderTravelerMapper, paymentMapper, refundMapper, reviewMapper, messageMapper, sysUserMapper,
                idempotencyRecordMapper, travelerMapper, alipayGatewayClient);
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

    // ---------------------------------------------------------------- 发起支付：配置闸门

    @Test
    @DisplayName("配置不齐时发起支付直接拒绝：409 PAYMENT_NOT_CONFIGURED，且不生成链接、不碰 payment 行")
    void startPaymentFailsClosedWhenConfigurationIncomplete() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(alipayGatewayClient.isCashierConfigurationComplete()).thenReturn(false);
        when(alipayGatewayClient.missingCashierConfiguration())
                .thenReturn(List.of("ALIPAY_PUBLIC_KEY", "ALIPAY_NOTIFY_URL"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.startPayment(o.orderNo, o.userId));

        assertEquals(409, ex.getStatus());
        assertEquals("PAYMENT_NOT_CONFIGURED", ex.getCode());
        // 提示必须点名缺哪一项，否则运维只能靠翻代码找
        assertTrue(ex.getMessage().contains("ALIPAY_PUBLIC_KEY"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ALIPAY_NOTIFY_URL"), ex.getMessage());

        // 关键证据：既没有生成过收银台链接，也没有碰过 payment 行
        // ——「配置不齐就不许产生可付款链接」这条策略的直接体现。
        verify(alipayGatewayClient, never()).buildCashierUrl(anyString(), any(), anyString());
        verify(paymentMapper, never()).selectOne(any());
        verify(paymentMapper, never()).updateById(any(Payment.class));
    }

    @Test
    @DisplayName("订单状态不允许支付时优先报状态冲突，不把「已支付」误报成「支付未配置」")
    void startPaymentReportsStateConflictBeforeConfiguration() {
        TravelOrder o = order(55L, OrderStatus.PAID_WAIT_CONFIRM, PaymentStatus.PAID);
        when(orderMapper.selectOne(any())).thenReturn(o);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.startPayment(o.orderNo, o.userId));

        // 状态是用户侧的因、配置是运维侧的问题，顺序反了会把排障方向带偏。
        // 注意这里【没有】stub 配置齐全性，mock 默认返回 false，能过就说明状态校验确实在前面。
        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
    }

    @Test
    @DisplayName("配置齐备时才生成真实收银台链接：paymentUrl 来自适配器，不回退占位地址")
    void startPaymentReturnsAdapterCashierUrlWhenConfigurationComplete() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.PENDING);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);
        when(alipayGatewayClient.isCashierConfigurationComplete()).thenReturn(true);
        when(alipayGatewayClient.buildCashierUrl(anyString(), any(), anyString())).thenReturn(CASHIER_URL);

        PaymentStartResponse response = orderService.startPayment(o.orderNo, o.userId);

        assertEquals(CASHIER_URL, response.paymentUrl());
        assertEquals(o.orderNo, response.orderNo());
        assertEquals(o.totalAmount, response.amount());
        assertNotNull(response.expiresAt());
        // 链接的 out_trade_no 必须是订单号（否则支付宝回传的 out_trade_no 对不上订单）
        verify(alipayGatewayClient).buildCashierUrl(eq(o.orderNo), eq(o.totalAmount), contains(o.orderNo));
        verify(paymentMapper).updateById(p);
        assertEquals(PaymentStatus.PENDING, p.status);
    }
}
