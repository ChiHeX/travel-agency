package com.travelagency.domain.service;

import com.travelagency.common.alipay.AlipayGatewayClient;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.enums.RefundStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.exception.RefundPendingConfirmationException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock
    private TravelerMapper travelerMapper;
    /** 支付宝沙箱适配器：收银台与退款出款都经它，用例按需桩「配置齐备」与出款结果。 */
    @Mock
    private AlipayGatewayClient alipayGatewayClient;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderMapper, departureMapper, routeMapper, guideMapper,
                orderTravelerMapper, paymentMapper, refundMapper, reviewMapper, messageMapper, sysUserMapper,
                idempotencyRecordMapper, travelerMapper, alipayGatewayClient);
    }

    /**
     * 走「审核通过」必须先把出款链路桩成可用：配置齐备 + 支付宝返回成功。
     *
     * <p>不加这两条桩，被测代码会在出款闸门处停下（{@code isRefundConfigurationComplete()}
     * 默认返回 false → 409），根本走不到状态流转，用例会以与意图无关的原因失败。</p>
     */
    private void givenRefundPayoutSucceeds(String orderNo) {
        when(alipayGatewayClient.isRefundConfigurationComplete()).thenReturn(true);
        when(alipayGatewayClient.refund(any(), any(), any(), any()))
                .thenReturn(AlipayGatewayClient.RefundResult.succeeded("2027030122001400000000000001", orderNo));
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
    @DisplayName("审核通过：先真出款，成功后才转 REFUNDED 并释放已确认名额")
    void approveSettlesOrderAndReleasesSeats() {
        Refund r = refund(70L, 55L, RefundStatus.APPLYING, OrderStatus.CONFIRMED);
        TravelOrder o = order(55L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        Payment p = payment(55L, PaymentStatus.PAID);
        when(refundMapper.selectById(70L)).thenReturn(r);
        when(orderMapper.selectById(55L)).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(p);
        // 抢占 APPLYING → PROCESSING 成功
        when(refundMapper.update(any(), any())).thenReturn(1);
        givenRefundPayoutSucceeds(o.orderNo);

        orderService.processRefund(70L, "APPROVE", "同意退款", 1L);

        assertEquals(RefundStatus.REFUNDED, r.status);
        assertEquals(1L, r.reviewedBy);
        assertEquals("同意退款", r.reviewComment);
        assertEquals(OrderStatus.REFUNDED, o.status);
        assertEquals(PaymentStatus.REFUNDED, o.paymentStatus);
        assertEquals(PaymentStatus.REFUNDED, p.status);

        // 出款参数的契约：商户订单号＝orderNo、请求号由退款单主键派生且稳定、金额与退款单一致
        verify(alipayGatewayClient).refund(o.orderNo, "RF70", new BigDecimal("2500.00"), r.reason);
        // 状态推进全部走带旧状态条件的 UPDATE：入口抢占 APPLYING→PROCESSING、出口幂等闸门 →REFUNDED。
        // 不再用 updateById 直接覆盖，是因为出口那次必须靠影响行数判断「是不是只有我推进了这一次」。
        verify(refundMapper, times(2)).update(any(), any());
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
        givenRefundPayoutSucceeds(o.orderNo);

        orderService.processRefund(71L, "APPROVE", "同意", 1L);

        assertEquals(RefundStatus.REFUNDED, r.status);
        verify(departureMapper).update(any(), any());
        // 未确认的订单没有占用 valid_booking_count，不应回退
        verify(routeMapper, never()).update(any(), any());
    }

    // ---------------------------------------------------------------- 出款（本案重点）

    @Test
    @DisplayName("出款配置不齐：拒绝审核通过，不落 REFUNDED、不释放名额、不调支付宝")
    void approveFailsClosedWhenPayoutIsNotConfigured() {
        Refund r = refund(90L, 70L, RefundStatus.APPLYING, OrderStatus.CONFIRMED);
        TravelOrder o = order(70L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(90L)).thenReturn(r);
        when(orderMapper.selectById(70L)).thenReturn(o);
        when(refundMapper.update(any(), any())).thenReturn(1);
        when(alipayGatewayClient.isRefundConfigurationComplete()).thenReturn(false);
        when(alipayGatewayClient.missingRefundConfiguration())
                .thenReturn(java.util.List.of("ALIPAY_APP_ID", "ALIPAY_APP_PRIVATE_KEY"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.processRefund(90L, "APPROVE", "同意", 1L));

        assertEquals(409, ex.getStatus());
        assertEquals("REFUND_NOT_CONFIGURED", ex.getCode());
        // 缺失项以环境变量名给出，便于运维定位，且不泄露密钥
        assertTrue(ex.getMessage().contains("ALIPAY_APP_ID"), "错误信息应指出缺哪个变量");
        assertTrue(ex.getMessage().contains("ALIPAY_APP_PRIVATE_KEY"));
        // 关键：钱没退，就不能有任何「已退款」的痕迹
        verify(alipayGatewayClient, never()).refund(any(), any(), any(), any());
        assertEquals(RefundStatus.APPLYING, r.status);
        assertEquals(OrderStatus.REFUND_APPLYING, o.status);
        verify(departureMapper, never()).update(any(), any());
        verify(routeMapper, never()).update(any(), any());
        verify(paymentMapper, never()).updateById(any(Payment.class));
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("支付宝退款未成功：抛出 503 REFUND_FAILED，且名额、状态、支付单都不动")
    void approveFailsClosedWhenGatewayRejectsTheRefund() {
        Refund r = refund(91L, 71L, RefundStatus.APPLYING, OrderStatus.CONFIRMED);
        TravelOrder o = order(71L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(91L)).thenReturn(r);
        when(orderMapper.selectById(71L)).thenReturn(o);
        when(refundMapper.update(any(), any())).thenReturn(1);
        when(alipayGatewayClient.isRefundConfigurationComplete()).thenReturn(true);
        when(alipayGatewayClient.refund(any(), any(), any(), any()))
                .thenReturn(AlipayGatewayClient.RefundResult.failed(
                        null, "ACQ.TRADE_NOT_EXIST", "交易不存在"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.processRefund(91L, "APPROVE", "同意", 1L));

        // 503：API.md §7 把「第三方服务暂时不可用」定在 503，表里没有 502
        assertEquals(503, ex.getStatus());
        assertEquals("REFUND_FAILED", ex.getCode());
        // 失败原因要能传到审核人眼里，否则无从判断该不该重试
        assertTrue(ex.getMessage().contains("ACQ.TRADE_NOT_EXIST"), "错误信息应带上支付宝错误码");
        assertEquals(RefundStatus.APPLYING, r.status, "出款失败必须留在 APPLYING 供重试");
        assertEquals(OrderStatus.REFUND_APPLYING, o.status);
        verify(departureMapper, never()).update(any(), any());
        verify(routeMapper, never()).update(any(), any());
        verify(paymentMapper, never()).updateById(any(Payment.class));
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("出款结果未确认（超时且查询也无结论）：结果码与「明确失败」分开，且绝不落任何已退款痕迹")
    void approveKeepsRefundPendingConfirmationWhenPayoutResultIsUnknown() {
        Refund r = refund(93L, 73L, RefundStatus.APPLYING, OrderStatus.CONFIRMED);
        TravelOrder o = order(73L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(93L)).thenReturn(r);
        when(orderMapper.selectById(73L)).thenReturn(o);
        when(refundMapper.update(any(), any())).thenReturn(1);
        when(alipayGatewayClient.isRefundConfigurationComplete()).thenReturn(true);
        when(alipayGatewayClient.refund(any(), any(), any(), any()))
                .thenReturn(AlipayGatewayClient.RefundResult.unconfirmed(
                        "退款请求异常：Read timed out；查询也失败：Read timed out"));

        RefundPendingConfirmationException ex = assertThrows(RefundPendingConfirmationException.class,
                () -> orderService.processRefund(93L, "APPROVE", "同意", 1L));

        // 与明确失败分开的结果码：调用方据此知道「钱可能已经退了，只能继续确认」
        assertEquals(503, ex.getStatus());
        assertEquals("REFUND_RESULT_UNCONFIRMED", ex.getCode());
        // 钱没确认就不能有「已退款」痕迹，也不能动订单 / 支付单 / 名额 / 线路计数
        assertEquals(OrderStatus.REFUND_APPLYING, o.status);
        verify(departureMapper, never()).update(any(), any());
        verify(routeMapper, never()).update(any(), any());
        verify(paymentMapper, never()).updateById(any(Payment.class));
        verify(messageMapper, never()).insert(any(Message.class));
        // 待确认状态是留给事务提交的：这里绝不能再把它写回 APPLYING ——
        // 一旦退回待审核，管理员就能「拒绝」，而拒绝会把已退款的订单恢复成已支付。
        verify(refundMapper, never()).updateById(any(Refund.class));
    }

    @Test
    @DisplayName("退款实际成功但结果未确认时：拒绝申请被拦下，订单保持已支付不被恢复")
    void rejectIsRefusedWhilePayoutResultIsUnconfirmed() {
        // PROCESSING 的由来：第一次「同意」时支付宝侧其实已经退款成功，但退款响应与随后的
        // 结果查询都超时，于是退款单被落成持久的「待确认」。
        Refund r = refund(94L, 74L, RefundStatus.PROCESSING, OrderStatus.CONFIRMED);
        TravelOrder o = order(74L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(94L)).thenReturn(r);
        when(orderMapper.selectById(74L)).thenReturn(o);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.processRefund(94L, "REJECT", "材料不齐", 1L));

        assertEquals(409, ex.getStatus());
        assertEquals("REFUND_RESULT_UNCONFIRMED", ex.getCode());
        // 关键：钱可能已经退出去，就绝不能再把订单恢复成申请前的 CONFIRMED
        assertEquals(OrderStatus.REFUND_APPLYING, o.status);
        verify(orderMapper, never()).updateById(any(TravelOrder.class));
        verify(refundMapper, never()).updateById(any(Refund.class));
        verify(refundMapper, never()).update(any(), any());
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("待确认重试：用同一请求号再次审核，拿到确定成功后才落 REFUNDED 并释放名额")
    void approveRetryAfterUnconfirmedOutcomeConfirmsAndSettles() {
        Refund r = refund(95L, 75L, RefundStatus.PROCESSING, OrderStatus.CONFIRMED);
        TravelOrder o = order(75L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(95L)).thenReturn(r);
        when(orderMapper.selectById(75L)).thenReturn(o);
        // 待确认重试没有可抢的状态迁移（进来时已是 PROCESSING ⇒ 抢占 0 行），
        // 靠出口的幂等闸门判定这次确实由本请求推进 ⇒ 返回 1 行。
        when(refundMapper.update(any(), any())).thenReturn(0, 1);
        when(paymentMapper.selectOne(any())).thenReturn(payment(75L, PaymentStatus.PAID));
        givenRefundPayoutSucceeds(o.orderNo);

        orderService.processRefund(95L, "APPROVE", "再次审核", 1L);

        assertEquals(RefundStatus.REFUNDED, r.status);
        assertEquals(OrderStatus.REFUNDED, o.status);
        assertEquals(PaymentStatus.REFUNDED, o.paymentStatus);
        // 重试必须仍用同一个 out_request_no，支付宝才会把第二次当成同一笔退款而不重复出款
        verify(alipayGatewayClient).refund(o.orderNo, "RF95", new BigDecimal("2500.00"), r.reason);
        verify(departureMapper).update(any(), any());
    }

    @Test
    @DisplayName("待确认重试被并发抢先：幂等闸门只放行一个，名额不会被释放两次")
    void unconfirmedRetryReleasesSeatsOnlyOnce() {
        Refund r = refund(96L, 76L, RefundStatus.PROCESSING, OrderStatus.CONFIRMED);
        TravelOrder o = order(76L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(96L)).thenReturn(r);
        when(orderMapper.selectById(76L)).thenReturn(o);
        // 抢占 0 行（状态已是 PROCESSING）；出口闸门也 0 行 ⇒ 另一个并发重试已经把它推到 REFUNDED
        when(refundMapper.update(any(), any())).thenReturn(0);
        when(paymentMapper.selectOne(any())).thenReturn(payment(76L, PaymentStatus.PAID));
        givenRefundPayoutSucceeds(o.orderNo);

        orderService.processRefund(96L, "APPROVE", "再次审核", 1L);

        // 出款那两次调用是安全的（请求号恒定 ⇒ 支付宝幂等），但**不可重复的副作用必须只发生一次**
        verify(departureMapper, never()).update(any(), any());
        verify(routeMapper, never()).update(any(), any());
        verify(orderMapper, never()).updateById(any(TravelOrder.class));
        verify(paymentMapper, never()).updateById(any(Payment.class));
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("同一退款单重试时出款请求号恒定 —— 支付宝侧据此幂等，不会重复出款")
    void payoutRequestNumberIsStableForTheSameRefund() {
        Refund r = refund(92L, 72L, RefundStatus.APPLYING, OrderStatus.CONFIRMED);
        TravelOrder o = order(72L, OrderStatus.REFUND_APPLYING, OrderStatus.CONFIRMED);
        when(refundMapper.selectById(92L)).thenReturn(r);
        when(orderMapper.selectById(72L)).thenReturn(o);
        when(refundMapper.update(any(), any())).thenReturn(1);
        // 成功路径会走到「支付单也要置为 REFUNDED」，缺这条桩会在 paymentFor 处中断
        when(paymentMapper.selectOne(any())).thenReturn(payment(72L, PaymentStatus.PAID));
        when(alipayGatewayClient.isRefundConfigurationComplete()).thenReturn(true);
        when(alipayGatewayClient.refund(any(), any(), any(), any()))
                .thenReturn(AlipayGatewayClient.RefundResult.failed(null, "aop.unknown-error", "系统繁忙"));

        // 第一次失败
        assertThrows(BusinessException.class,
                () -> orderService.processRefund(92L, "APPROVE", "同意", 1L));
        // 后台原样重试（状态仍是 APPLYING）
        when(alipayGatewayClient.refund(any(), any(), any(), any()))
                .thenReturn(AlipayGatewayClient.RefundResult.succeeded("2027030122001400000000000009", o.orderNo));
        orderService.processRefund(92L, "APPROVE", "同意", 1L);

        assertEquals(RefundStatus.REFUNDED, r.status);
        // 两次调用必须是同一个 out_request_no，否则支付宝会把重试当成新的一次退款
        verify(alipayGatewayClient, times(2)).refund(eq(o.orderNo), eq("RF92"), any(), any());
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
