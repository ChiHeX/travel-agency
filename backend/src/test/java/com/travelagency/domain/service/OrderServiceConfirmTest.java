package com.travelagency.domain.service;

import com.travelagency.common.alipay.AlipayGatewayClient;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.exception.OrderAuditAnomalyException;
import com.travelagency.domain.dto.OrderView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.OrderTraveler;
import com.travelagency.domain.entity.Payment;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.IdempotencyRecordMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.OrderTravelerMapper;
import com.travelagency.domain.mapper.PaymentMapper;
import com.travelagency.domain.mapper.RefundMapper;
import com.travelagency.domain.mapper.ReviewMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelerMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员 B 交易模块：工作人员确认报名的状态机、业务复核与异常通知。
 *
 * <p>契约 {@code POST /admin/orders/{orderNo}/confirm} 的 200 响应是 {@code OrderEnvelope}，
 * 因此服务层必须返回确认后的订单视图；只改状态不返回订单会让响应 data 为 null。</p>
 *
 * <p>覆盖三类约束：</p>
 * <ol>
 *   <li><b>并发安全</b>：状态迁移闸门拿不到 1 行影响时不得继续做副作用（名额迁移、统计、通知）。</li>
 *   <li><b>业务复核</b>：支付未到账 / 快照数量不符 / 实名缺失时不得进入 {@code CONFIRMED}，不动库存与统计。</li>
 *   <li><b>异常通知</b>：复核异常必须留痕并给用户一条站内通知，且同一异常原因不重复通知、不泄露实名信息。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceConfirmTest {

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
    /** 支付宝沙箱适配器：确认链路不涉及出款，测试中只作为构造依赖。 */
    @Mock
    private AlipayGatewayClient alipayGatewayClient;
    /** 审计记录器：确认/异常留痕已移入业务事务，这里只关心它被调用以及结果态。 */
    @Mock
    private OperationLogRecorder operationLog;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderMapper, departureMapper, routeMapper, guideMapper,
                orderTravelerMapper, paymentMapper, refundMapper, reviewMapper, messageMapper, sysUserMapper,
                idempotencyRecordMapper, travelerMapper, alipayGatewayClient, operationLog);
    }

    private static TravelOrder order(long id, String status) {
        return order(id, status, PaymentStatus.PAID);
    }

    private static TravelOrder order(long id, String status, String paymentStatus) {
        TravelOrder o = new TravelOrder();
        o.id = id;
        o.orderNo = "TA20270301000001ABCD1234";
        o.userId = 9L;
        o.routeId = 100L;
        o.departureId = 7L;
        o.contactName = "联系人";
        o.contactPhone = "13800000000";
        o.adultCount = 2;
        o.childCount = 1;
        o.adultUnitPrice = new BigDecimal("1000.00");
        o.childUnitPrice = new BigDecimal("500.00");
        o.totalAmount = new BigDecimal("2500.00");
        o.status = status;
        o.paymentStatus = paymentStatus;
        return o;
    }

    private static Payment payment(String status) {
        Payment p = new Payment();
        p.id = 300L;
        p.orderId = 55L;
        p.paymentNo = "PAYTA20270301000001ABCD1234";
        p.channel = "ALIPAY_SANDBOX";
        p.amount = new BigDecimal("2500.00");
        p.status = status;
        return p;
    }

    /** 一份字段齐全的出行人快照：姓名 / 证件类型 / 证件号都在。 */
    private static OrderTraveler traveler(String name, String idNo) {
        OrderTraveler t = new OrderTraveler();
        t.orderId = 55L;
        t.travelerType = "ADULT";
        t.name = name;
        t.idType = "CHINESE_ID_CARD";
        t.idNo = idNo;
        return t;
    }

    private static Departure departure(String status) {
        Departure d = new Departure();
        d.id = 7L;
        d.routeId = 100L;
        d.startDate = LocalDate.of(2027, 3, 1);
        d.maxPeople = 10;
        d.reservedPeople = 3;
        d.confirmedPeople = 4;
        d.status = status;
        return d;
    }

    private static TravelRoute route() {
        TravelRoute r = new TravelRoute();
        r.id = 100L;
        r.name = "昆明·大理·丽江 6 日跟团游";
        r.coverUrl = "https://example.com/cover.jpg";
        return r;
    }

    /**
     * 把「复核能过」的桩一次打好：支付已到账、3 位出行人实名齐全、团期开放。
     * 订单登记 2 成人 + 1 儿童 = 3 人，因此快照也必须是 3 条。
     */
    private void givenReviewPasses(TravelOrder o) {
        when(paymentMapper.selectOne(any())).thenReturn(payment(PaymentStatus.PAID));
        when(orderTravelerMapper.selectList(any())).thenReturn(List.of(
                traveler("张三", "310101199001010011"),
                traveler("李四", "310101199001010022"),
                traveler("小明", "310101201501010033")));
        when(departureMapper.selectById(7L)).thenReturn(departure(DepartureStatus.OPEN));
    }

    // ------------------------------------------------------------------
    // 成功路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("确认报名：返回契约 OrderEnvelope 所需的订单视图，并回填 routeName/departureStartDate")
    void confirmReturnsUpdatedOrderView() {
        TravelOrder o = order(55L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        givenReviewPasses(o);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(departureMapper.update(any(), any())).thenReturn(1);
        when(routeMapper.selectById(100L)).thenReturn(route());

        OrderView view = orderService.confirm(o.orderNo, 77L);

        assertNotNull(view, "契约要求 data 为订单对象，不能为 null");
        assertEquals(OrderStatus.CONFIRMED, view.status());
        assertEquals(o.orderNo, view.orderNo());
        assertEquals("昆明·大理·丽江 6 日跟团游", view.routeName());
        assertEquals(LocalDate.of(2027, 3, 1), view.departureStartDate());
        assertEquals(new BigDecimal("2500.00"), view.totalAmount());
        assertNotNull(view.confirmedAt());
        // 名额从 reserved 转入 confirmed，线路有效报名数 +1
        verify(departureMapper).update(any(), any());
        verify(routeMapper).update(any(), any());
        verify(messageMapper, times(1)).insert(any(Message.class));
        // 业务留痕为成功态，且写在业务事务内
        verify(operationLog).record(any(), anyString(), anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.eq(OperationLogRecorder.SUCCESS), anyString());
    }

    @Test
    @DisplayName("非待确认订单不能确认，且不得改动名额")
    void confirmRejectsWrongStatus() {
        TravelOrder o = order(56L, OrderStatus.WAIT_PAY);
        when(orderMapper.selectOne(any())).thenReturn(o);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("团期已关闭时不能确认报名")
    void confirmRejectsClosedDeparture() {
        TravelOrder o = order(57L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        givenReviewPasses(o);
        when(departureMapper.selectById(7L)).thenReturn(departure(DepartureStatus.CLOSED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
    }

    /**
     * 并发安全（B-01）：这张订单已被另一位工作人员抢先在状态迁移闸门处推进，
     * 本次请求必须立刻停下 —— 不能再迁移名额、不能回填线路统计、不能发通知。
     */
    @Test
    @DisplayName("并发确认：状态迁移闸门未抢到时立即中止，名额/统计/通知一处都不动")
    void confirmStopsWhenOrderStatusClaimIsLost() {
        TravelOrder o = order(58L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        givenReviewPasses(o);
        when(orderMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
        verify(routeMapper, never()).update(any(), any());
        verify(messageMapper, never()).insert(any(Message.class));
        verify(operationLog, never()).record(any(), anyString(), anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.eq(OperationLogRecorder.SUCCESS), anyString());
    }

    @Test
    @DisplayName("名额抢占失败（并发确认）时报容量不足，订单状态不变")
    void confirmReportsCapacityConflict() {
        TravelOrder o = order(59L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        givenReviewPasses(o);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(departureMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_CAPACITY_INSUFFICIENT", ex.getCode());
        assertEquals(OrderStatus.PAID_WAIT_CONFIRM, o.status);
        verify(routeMapper, never()).update(any(), any());
        verify(messageMapper, never()).insert(any(Message.class));
    }

    // ------------------------------------------------------------------
    // 业务复核（B-02）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("复核：支付未到账时拒绝确认，不动名额与统计，但留痕并通知用户")
    void confirmRejectsUnsettledPayment() {
        TravelOrder o = order(60L, OrderStatus.PAID_WAIT_CONFIRM, PaymentStatus.UNPAID);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(payment(PaymentStatus.UNPAID));

        OrderAuditAnomalyException ex = assertThrows(OrderAuditAnomalyException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(409, ex.getStatus());
        assertEquals(OrderAuditAnomalyException.CODE, ex.getCode());
        assertEquals(OrderAuditAnomalyException.PAYMENT_NOT_SETTLED, ex.reasonCode());
        assertNoBusinessWrites();
        assertAnomalyRecorded();
        assertNotifiedOnce();
    }

    @Test
    @DisplayName("复核：出行人快照数量与订单登记人数不一致时拒绝确认")
    void confirmRejectsTravelerSnapshotMismatch() {
        TravelOrder o = order(61L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(payment(PaymentStatus.PAID));
        // 订单登记 3 人，快照只有 2 条
        when(orderTravelerMapper.selectList(any())).thenReturn(List.of(
                traveler("张三", "310101199001010011"),
                traveler("李四", "310101199001010022")));

        OrderAuditAnomalyException ex = assertThrows(OrderAuditAnomalyException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(OrderAuditAnomalyException.TRAVELER_SNAPSHOT_MISMATCH, ex.reasonCode());
        assertNoBusinessWrites();
        assertAnomalyRecorded();
        assertNotifiedOnce();
    }

    /**
     * 实名缺失时必须只回「第几位 + 缺哪个字段」，不得把姓名、证件号带进接口 message 或通知正文。
     */
    @Test
    @DisplayName("复核：实名信息缺失时拒绝确认，且异常说明与通知正文都不泄露姓名/证件号")
    void confirmRejectsIncompleteIdentityWithoutLeakingPersonalData() {
        TravelOrder o = order(62L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(payment(PaymentStatus.PAID));
        when(orderTravelerMapper.selectList(any())).thenReturn(List.of(
                traveler("张三", "310101199001010011"),
                traveler("李四", null),
                traveler("小明", "310101201501010033")));

        OrderAuditAnomalyException ex = assertThrows(OrderAuditAnomalyException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(OrderAuditAnomalyException.TRAVELER_IDENTITY_INCOMPLETE, ex.reasonCode());
        assertTrue(ex.getMessage().contains("第 2 位"), "应指出是第几位出行人： " + ex.getMessage());
        assertFalse(ex.getMessage().contains("李四"), "接口 message 不得含出行人姓名");
        assertFalse(ex.getMessage().contains("310101"), "接口 message 不得含证件号片段");

        Message message = captureSingleMessage();
        assertFalse(message.content.contains("李四"), "通知正文不得含出行人姓名");
        assertFalse(message.content.contains("310101"), "通知正文不得含证件号片段");
        assertTrue(message.content.contains("第 2 位"), "通知正文应说明是哪一位出行人： " + message.content);
        assertNoBusinessWrites();
    }

    /**
     * 反复点确认同一张问题订单：审计留痕每次都有（这是操作记录），站内通知只发一次。
     */
    @Test
    @DisplayName("复核：同一异常原因重复确认时，用户只会收到一条通知")
    void repeatedAnomalyNotifiesOnlyOnce() {
        TravelOrder o = order(63L, OrderStatus.PAID_WAIT_CONFIRM, PaymentStatus.UNPAID);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(paymentMapper.selectOne(any())).thenReturn(payment(PaymentStatus.UNPAID));
        // 上一次确认已经写过同标题的通知
        when(messageMapper.selectCount(any())).thenReturn(1L);

        OrderAuditAnomalyException ex = assertThrows(OrderAuditAnomalyException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(OrderAuditAnomalyException.PAYMENT_NOT_SETTLED, ex.reasonCode());
        verify(messageMapper, never()).insert(any(Message.class));
        assertAnomalyRecorded();
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    /** 审核异常发生在任何写入之前：订单、名额、线路统计都必须原封不动。 */
    private void assertNoBusinessWrites() {
        verify(orderMapper, never()).update(any(), any());
        verify(departureMapper, never()).update(any(), any());
        verify(routeMapper, never()).update(any(), any());
    }

    /** 审核异常必须留下一条 FAILURE 留痕（操作记录与是否通知用户是两件事）。 */
    private void assertAnomalyRecorded() {
        verify(operationLog).record(any(), anyString(), org.mockito.ArgumentMatchers.eq("AUDIT_ANOMALY"),
                anyString(), any(),
                org.mockito.ArgumentMatchers.eq(OperationLogRecorder.FAILURE), anyString());
    }

    /** 审核异常必须让用户看到一条站内通知。 */
    private void assertNotifiedOnce() {
        verify(messageMapper, times(1)).insert(any(Message.class));
    }

    private Message captureSingleMessage() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        Message message = captor.getValue();
        assertEquals("ORDER_AUDIT_ANOMALY", message.type, "通知类型应标识为订单审核异常");
        assertEquals(9L, message.userId, "通知应发给下单用户");
        assertNotNull(message.title);
        return message;
    }
}
