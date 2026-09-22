package com.travelagency.domain.service;

import com.travelagency.common.alipay.AlipayGatewayClient;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员 B 交易模块：支付回调幂等与发起支付的幂等键。
 * 支付宝会重复投递异步通知，重复处理绝不能把订单推进两次；
 * 同一幂等键重试 {@code POST /orders/{orderNo}/pay} 也只能真正发起一次支付。
 *
 * <p>同步 dev 之后发起支付多了「配置不齐就 409 PAYMENT_NOT_CONFIGURED」的闸门，
 * 因此本类里凡是会走到「生成收银台链接」的用例都要先 {@link #givenCashierConfigured()}
 * 声明配置齐备；闸门本身的用例见文件末尾「发起支付：配置闸门」一节。</p>
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

    /**
     * 让「配置齐备」分支生效：适配器校验通过，并且签得出的是一条<b>真实收银台链接</b>。
     *
     * <p>同步 dev 之后发起支付多了一道「配置不齐就 409」的闸门，凡是走到「生成链接」这一步的
     * 用例都必须显式声明配置齐备；否则 mock 的默认返回 false 会让用例死在闸门上，
     * 看起来像幂等逻辑坏了，其实是前提没给。</p>
     */
    private void givenCashierConfigured() {
        when(alipayGatewayClient.isCashierConfigurationComplete()).thenReturn(true);
        when(alipayGatewayClient.buildCashierUrl(anyString(), any(), anyString())).thenReturn(CASHIER_URL);
    }

    @Test
    @DisplayName("幂等：同一幂等键重复发起支付只返回同一个支付单，且不改写支付单、不滑动有效期")
    void startPaymentReplaysSamePaymentWithoutTouchingTheRow() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.UNPAID);
        LocalDateTime anchor = LocalDateTime.now().minusMinutes(5);
        givenCashierConfigured();
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
    @DisplayName("配置齐备时重放：支付单与有效期锚点不变，链接每次都由适配器重新签发（同一下单号）")
    void startPaymentReplaysSamePaymentAndWindowWithNewlySignedLink() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.UNPAID);
        LocalDateTime anchor = LocalDateTime.now().minusMinutes(5);
        givenCashierConfigured();
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.selectById(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);
        when(idempotencyRecordMapper.insert(any(IdempotencyRecord.class))).thenReturn(1);
        when(idempotencyRecordMapper.selectOne(any())).thenReturn(idempotency(null, anchor));

        PaymentStartResponse first = orderService.startPayment(o.orderNo, o.userId, "IDEM-PAY-0001");

        givenKeyAlreadyClaimed(idempotency(p.paymentNo, anchor));
        PaymentStartResponse replay = orderService.startPayment(o.orderNo, o.userId, "IDEM-PAY-0001");

        // 幂等口径：支付单与支付窗口锚点都必须与首次一致
        assertEquals(first.paymentNo(), replay.paymentNo());
        assertEquals(anchor.plusMinutes(30), first.expiresAt());
        assertEquals(first.expiresAt(), replay.expiresAt());

        // 但收银台链接是「每次现签」的：两次响应都来自适配器，且都用同一个下单号与金额。
        // 这是实际行为，也是对的——alipay.trade.page.pay 的公共参数含 timestamp，
        // 想让重放返回逐字相同的 URL 只能先把链接存库，而契约（openapi.yaml）只要求
        // paymentUrl 是 uri，真正决定「是不是同一笔交易」的是 out_trade_no。
        assertEquals(CASHIER_URL, first.paymentUrl());
        assertEquals(CASHIER_URL, replay.paymentUrl());
        verify(alipayGatewayClient, times(2)).buildCashierUrl(anyString(), any(), anyString());
        verify(alipayGatewayClient, times(2))
                .buildCashierUrl(eq(o.orderNo), eq(o.totalAmount), contains(o.orderNo));

        // 业务侧仍然只写一次：重放没有二次副作用
        verify(paymentMapper, times(1)).updateById(p);
    }

    @Test
    @DisplayName("幂等：订单已支付后用同一键重试，返回首次的支付单而不是 409")
    void startPaymentReplayWinsOverStateCheck() {
        // 订单已推进到 PAID_WAIT_CONFIRM；同一幂等键的重试属于原请求的重放，应拿到首次结果
        TravelOrder o = order(55L, OrderStatus.PAID_WAIT_CONFIRM, PaymentStatus.PAID);
        Payment p = payment(55L, PaymentStatus.PAID);
        givenCashierConfigured();
        givenKeyAlreadyClaimed(idempotency(p.paymentNo, LocalDateTime.now().minusMinutes(5)));
        when(paymentMapper.selectOne(any())).thenReturn(p);
        when(orderMapper.selectById(any())).thenReturn(o);

        PaymentStartResponse replay = orderService.startPayment(o.orderNo, o.userId, "IDEM-PAY-0001");

        assertEquals(p.paymentNo, replay.paymentNo());
        // 重放拿到的还是首次那条已签名链接，订单状态变化不会把重放变成 409
        assertEquals(CASHIER_URL, replay.paymentUrl());
        assertEquals(OrderStatus.PAID_WAIT_CONFIRM, o.status);
        verify(paymentMapper, never()).updateById(any(Payment.class));
        verify(alipayGatewayClient, never()).isCashierConfigurationComplete();
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
    @DisplayName("不带幂等键、配置齐备时：改支付单为 PENDING，按当前时刻给 30 分钟窗口并返回已签名链接")
    void startPaymentWithoutKeyKeepsLegacyBehaviour() {
        TravelOrder o = order(55L, OrderStatus.WAIT_PAY, PaymentStatus.UNPAID);
        Payment p = payment(55L, PaymentStatus.UNPAID);
        givenCashierConfigured();
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);

        PaymentStartResponse response = orderService.startPayment(o.orderNo, o.userId);

        assertEquals(PaymentStatus.PENDING, p.status);
        assertEquals(p.paymentNo, response.paymentNo());
        assertEquals(o.totalAmount, response.amount());
        assertEquals(CASHIER_URL, response.paymentUrl());
        assertTrue(response.expiresAt().isAfter(LocalDateTime.now().plusMinutes(29)));
        verify(paymentMapper).updateById(p);
        verify(idempotencyRecordMapper, never()).insert(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("状态闸门没有被幂等放宽：不带幂等键时非待支付订单仍报 409，且不触碰适配器")
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
        // 状态不对时连配置齐全性都不必问，更不该去签链接
        verify(alipayGatewayClient, never()).isCashierConfigurationComplete();
        verify(alipayGatewayClient, never()).buildCashierUrl(anyString(), any(), anyString());
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
