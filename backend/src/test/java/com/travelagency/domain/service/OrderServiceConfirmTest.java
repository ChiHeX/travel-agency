package com.travelagency.domain.service;

import com.travelagency.common.alipay.AlipayGatewayClient;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.OrderView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Message;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员 B 交易模块：工作人员确认报名的状态机与响应形状。
 *
 * <p>契约 {@code POST /admin/orders/{orderNo}/confirm} 的 200 响应是 {@code OrderEnvelope}，
 * 因此服务层必须返回确认后的订单视图；只改状态不返回订单会让响应 data 为 null。</p>
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

    private static TravelOrder order(long id, String status) {
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
        o.paymentStatus = PaymentStatus.PAID;
        return o;
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

    @Test
    @DisplayName("确认报名：返回契约 OrderEnvelope 所需的订单视图，并回填 routeName/departureStartDate")
    void confirmReturnsUpdatedOrderView() {
        TravelOrder o = order(55L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(departureMapper.selectById(7L)).thenReturn(departure(DepartureStatus.OPEN));
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
        // 名额从 reserved 转入 confirmed
        verify(departureMapper).update(any(), any());
        verify(routeMapper).update(any(), any());
        verify(messageMapper).insert(any(Message.class));
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
        verify(orderMapper, never()).updateById(any(TravelOrder.class));
    }

    @Test
    @DisplayName("团期已关闭时不能确认报名")
    void confirmRejectsClosedDeparture() {
        TravelOrder o = order(57L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(departureMapper.selectById(7L)).thenReturn(departure(DepartureStatus.CLOSED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("名额抢占失败（并发确认）时报容量不足，订单状态不变")
    void confirmReportsCapacityConflict() {
        TravelOrder o = order(58L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(departureMapper.selectById(7L)).thenReturn(departure(DepartureStatus.OPEN));
        when(departureMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirm(o.orderNo, 77L));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_CAPACITY_INSUFFICIENT", ex.getCode());
        assertEquals(OrderStatus.PAID_WAIT_CONFIRM, o.status);
        verify(orderMapper, never()).updateById(any(TravelOrder.class));
    }

    // ------------------------------------------------------------------
    // 报名审核异常通知（PRD §13 / §29）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("审核异常：只发通知，不动订单状态、不动名额，返回契约 OrderEnvelope")
    void flagReviewExceptionNotifiesWithoutChangingState() {
        TravelOrder o = order(59L, OrderStatus.PAID_WAIT_CONFIRM);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(departureMapper.selectById(7L)).thenReturn(departure(DepartureStatus.OPEN));
        when(routeMapper.selectById(100L)).thenReturn(route());

        OrderView view = orderService.flagReviewException(o.orderNo, "出行人证件信息与订单不符，请补充");

        assertEquals(OrderStatus.PAID_WAIT_CONFIRM, view.status(), "审核异常不是状态迁移，订单必须停在待确认");
        assertEquals(o.orderNo, view.orderNo());

        org.mockito.ArgumentCaptor<Message> captor =
                org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        Message message = captor.getValue();
        assertEquals(o.userId, message.userId, "消息必须投递给下单用户");
        assertEquals("ORDER_REVIEW_EXCEPTION", message.type);
        org.junit.jupiter.api.Assertions.assertTrue(
                message.content.contains("出行人证件信息与订单不符，请补充"),
                "工作人员填写的说明必须原样带给用户，实际：" + message.content);
        assertEquals(0, message.readFlag, "新消息未读");

        // 名额与线路计数都不受影响：既不释放预留，也不计入有效报名。
        verify(departureMapper, never()).update(any(), any());
        verify(routeMapper, never()).update(any(), any());
        verify(orderMapper, never()).updateById(any(TravelOrder.class));
    }

    @Test
    @DisplayName("审核异常：已确认/已取消等非待确认订单返回 409，且不发消息")
    void flagReviewExceptionRejectsOrdersOutsideReview() {
        for (String status : new String[]{OrderStatus.CONFIRMED, OrderStatus.CANCELLED,
                OrderStatus.WAIT_PAY, OrderStatus.COMPLETED}) {
            TravelOrder o = order(60L, status);
            when(orderMapper.selectOne(any())).thenReturn(o);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.flagReviewException(o.orderNo, "资料不全"),
                    status + " 不应允许标记审核异常");

            assertEquals(409, ex.getStatus());
            assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        }
        verify(messageMapper, never()).insert(any(Message.class));
    }
}
