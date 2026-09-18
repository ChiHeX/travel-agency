package com.travelagency.domain.service;

import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.enums.TravelerType;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.CreateOrderRequest;
import com.travelagency.domain.dto.OrderView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.IdempotencyRecord;
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
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.mapper.TravelerMapper;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * 成员 B 交易模块：下单与名额预留。
 * 重点覆盖 PRD 4.6 要求的「下单名额并发防超卖」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceCreateTest {

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

    // ---------------------------------------------------------------- helpers

    private static Departure departure(long id, int max, int reserved, int confirmed, String status) {
        Departure d = new Departure();
        d.id = id;
        d.routeId = 100L;
        d.startDate = LocalDate.of(2027, 3, 1);
        d.endDate = LocalDate.of(2027, 3, 5);
        d.adultPrice = new BigDecimal("1000.00");
        d.childPrice = new BigDecimal("500.00");
        d.maxPeople = max;
        d.reservedPeople = reserved;
        d.confirmedPeople = confirmed;
        d.status = status;
        return d;
    }

    private static CreateOrderRequest.TravelerSnapshotRequest traveler(String name, String travelerType) {
        return traveler(name, travelerType, null);
    }

    private static CreateOrderRequest.TravelerSnapshotRequest traveler(
            String name, String travelerType, Long sourceTravelerId) {
        return new CreateOrderRequest.TravelerSnapshotRequest(
                sourceTravelerId, name, "MALE", LocalDate.of(1990, 1, 1), "CHINESE_ID_CARD",
                "320100199001011234", "13800000000", "紧急联系人", "13900000000", travelerType);
    }

    private static CreateOrderRequest request(long departureId, int adults, int children, int travelerCount) {
        List<CreateOrderRequest.TravelerSnapshotRequest> travelers = new ArrayList<>();
        for (int i = 0; i < travelerCount; i++) {
            // 契约要求请求体显式给出 travelerType，这里按「前 adultCount 位为成人」构造
            travelers.add(traveler("出行人" + i, i < adults ? TravelerType.ADULT : TravelerType.CHILD));
        }
        return new CreateOrderRequest(departureId, adults, children, "联系人", "13800000000",
                "contact@example.com", travelers, "备注");
    }

    /** 让 orderMapper.insert 回填自增主键，并让 selectById 回查同一实例。 */
    private AtomicReference<TravelOrder> stubInsertReturningId(long id) {
        AtomicReference<TravelOrder> ref = new AtomicReference<>();
        when(orderMapper.insert(any(TravelOrder.class))).thenAnswer(inv -> {
            TravelOrder o = inv.getArgument(0);
            o.id = id;
            o.createdAt = LocalDateTime.of(2027, 2, 1, 10, 0);
            o.updatedAt = LocalDateTime.of(2027, 2, 1, 10, 0);
            ref.set(o);
            return 1;
        });
        when(orderMapper.selectById(id)).thenAnswer(inv -> ref.get());
        return ref;
    }

    // ---------------------------------------------------------------- 正常路径

    @Test
    @DisplayName("正常下单：按人数计价、预留名额、写出行人快照与支付单")
    void createsOrderAndReservesCapacity() {
        when(departureMapper.selectById(7L)).thenReturn(departure(7L, 10, 2, 3, DepartureStatus.OPEN));
        when(departureMapper.update(any(), any())).thenReturn(1);
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        stubInsertReturningId(55L);

        OrderView view = orderService.create(9L, request(7L, 2, 1, 3));

        // 总价 = 1000*2 + 500*1
        assertEquals(new BigDecimal("2500.00"), view.totalAmount());
        assertEquals(OrderStatus.WAIT_PAY, view.status());
        assertEquals(PaymentStatus.UNPAID, view.paymentStatus());
        assertEquals(9L, view.userId());
        assertNotNull(view.orderNo());

        // 出行人快照：前 adultCount 位为成人，其余为儿童
        ArgumentCaptor<OrderTraveler> travelerCaptor = ArgumentCaptor.forClass(OrderTraveler.class);
        verify(orderTravelerMapper, times(3)).insert(travelerCaptor.capture());
        List<OrderTraveler> snapshots = travelerCaptor.getAllValues();
        assertEquals(TravelerType.ADULT, snapshots.get(0).travelerType);
        assertEquals(TravelerType.ADULT, snapshots.get(1).travelerType);
        assertEquals(TravelerType.CHILD, snapshots.get(2).travelerType);

        // 支付单与订单同额
        verify(paymentMapper).insert(paymentCaptor.capture());
        assertEquals(new BigDecimal("2500.00"), paymentCaptor.getValue().amount);
    }

    @Test
    @DisplayName("显式类型：顺序被调换时仍按调用方指定的 travelerType 落快照")
    void honorsExplicitTravelerTypeRegardlessOfOrder() {
        when(departureMapper.selectById(7L)).thenReturn(departure(7L, 10, 0, 0, DepartureStatus.OPEN));
        when(departureMapper.update(any(), any())).thenReturn(1);
        stubInsertReturningId(57L);

        // 第 1 位是儿童、第 2 位是成人：按下标推断会写反
        CreateOrderRequest mixed = new CreateOrderRequest(7L, 1, 1, "联系人", "13800000000",
                "contact@example.com",
                List.of(traveler("小孩", TravelerType.CHILD), traveler("大人", TravelerType.ADULT)),
                "备注");

        orderService.create(9L, mixed, null);

        ArgumentCaptor<OrderTraveler> captor = ArgumentCaptor.forClass(OrderTraveler.class);
        verify(orderTravelerMapper, times(2)).insert(captor.capture());
        assertEquals(TravelerType.CHILD, captor.getAllValues().get(0).travelerType);
        assertEquals(TravelerType.ADULT, captor.getAllValues().get(1).travelerType);
    }

    @Test
    @DisplayName("来源常用出行人：sourceTravelerId 落库到 order_traveler.traveler_id")
    void persistsSourceTravelerIdOnSnapshot() {
        when(travelerMapper.selectCount(any())).thenReturn(1L);
        when(departureMapper.selectById(7L)).thenReturn(departure(7L, 10, 0, 0, DepartureStatus.OPEN));
        when(departureMapper.update(any(), any())).thenReturn(1);
        stubInsertReturningId(58L);

        CreateOrderRequest withSource = new CreateOrderRequest(7L, 1, 0, "联系人", "13800000000",
                "contact@example.com",
                List.of(traveler("大人", TravelerType.ADULT, 42L)),
                "备注");

        orderService.create(9L, withSource, null);

        ArgumentCaptor<OrderTraveler> captor = ArgumentCaptor.forClass(OrderTraveler.class);
        verify(orderTravelerMapper, times(1)).insert(captor.capture());
        assertEquals(Long.valueOf(42L), captor.getValue().travelerId);
    }

    @Test
    @DisplayName("来源常用出行人：不属于当前用户时拒绝且不占用名额")
    void rejectsUnownedSourceTravelerBeforeReservingCapacity() {
        when(travelerMapper.selectCount(any())).thenReturn(0L);
        CreateOrderRequest withUnownedSource = new CreateOrderRequest(7L, 1, 0, "联系人", "13800000000",
                "contact@example.com",
                List.of(traveler("大人", TravelerType.ADULT, 42L)),
                "备注");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(9L, withUnownedSource, null));

        assertEquals(404, ex.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
        verify(orderMapper, never()).insert(any(TravelOrder.class));
        verify(orderTravelerMapper, never()).insert(any(OrderTraveler.class));
    }

    @Test
    @DisplayName("显式类型：标记为成人的人数与 adultCount 不一致时拒绝下单")
    void rejectsExplicitTypeCountMismatch() {
        CreateOrderRequest mismatch = new CreateOrderRequest(7L, 1, 1, "联系人", "13800000000",
                "contact@example.com",
                List.of(traveler("小孩", TravelerType.CHILD), traveler("大人", TravelerType.CHILD)),
                "备注");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(9L, mismatch, null));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verify(orderMapper, never()).insert(any(TravelOrder.class));
    }

    // ---------------------------------------------------------------- 幂等键

    @Test
    @DisplayName("幂等键：同一键重复提交时返回首次订单，不再占用名额")
    void replaysFirstOrderForSameIdempotencyKey() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.userId = 9L;
        record.scope = "CREATE_ORDER";
        record.idemKey = "idem-key-0001";
        record.resourceType = "ORDER";
        record.resourceNo = "TA20270301000001ABCD1234";
        when(idempotencyRecordMapper.insert(any(IdempotencyRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_idempotency_user_scope_key"));
        when(idempotencyRecordMapper.selectOne(any())).thenReturn(record);

        TravelOrder existing = new TravelOrder();
        existing.id = 88L;
        existing.orderNo = record.resourceNo;
        existing.userId = 9L;
        existing.routeId = 100L;
        existing.departureId = 7L;
        existing.adultCount = 1;
        existing.childCount = 0;
        existing.totalAmount = new BigDecimal("1000.00");
        existing.status = OrderStatus.WAIT_PAY;
        existing.paymentStatus = PaymentStatus.UNPAID;
        when(orderMapper.selectOne(any())).thenReturn(existing);
        TravelRoute route = new TravelRoute();
        route.id = 100L;
        when(routeMapper.selectById(100L)).thenReturn(route);
        when(departureMapper.selectById(7L)).thenReturn(departure(7L, 10, 0, 0, DepartureStatus.OPEN));

        OrderView view = orderService.create(9L, request(7L, 1, 0, 1), "idem-key-0001");

        assertEquals(record.resourceNo, view.orderNo());
        // 重放请求不得再占名额、不得再落订单与支付单
        verify(departureMapper, never()).update(any(), any());
        verify(orderMapper, never()).insert(any(TravelOrder.class));
        verify(paymentMapper, never()).insert(any(Payment.class));
    }

    // ---------------------------------------------------------------- 防超卖

    @Test
    @DisplayName("防超卖：剩余名额不足时直接拒绝，且不产生订单")
    void rejectsWhenRemainingSeatsInsufficient() {
        // 已占 8 + 1 = 9，仅剩 1 个名额，本次要 2 人
        when(departureMapper.selectById(7L)).thenReturn(departure(7L, 10, 8, 1, DepartureStatus.OPEN));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(9L, request(7L, 2, 0, 2)));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_CAPACITY_INSUFFICIENT", ex.getCode());
        verify(orderMapper, never()).insert(any(TravelOrder.class));
        verify(orderTravelerMapper, never()).insert(any(OrderTraveler.class));
        verify(paymentMapper, never()).insert(any(Payment.class));
    }

    @Test
    @DisplayName("防超卖：并发抢占名额时乐观更新失败，订单不得落库")
    void doesNotPersistOrderWhenCapacityStolenConcurrently() {
        // 前置检查读到的还是旧值（够用），但带条件的 UPDATE 返回 0 表示名额已被他人抢走
        when(departureMapper.selectById(7L)).thenReturn(departure(7L, 10, 9, 0, DepartureStatus.OPEN));
        when(departureMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(9L, request(7L, 1, 0, 1)));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_CAPACITY_INSUFFICIENT", ex.getCode());
        // 关键：名额没抢到，绝不能落订单
        verify(orderMapper, never()).insert(any(TravelOrder.class));
    }

    @Test
    @DisplayName("防超卖：名额恰好用尽时仍可下单（边界值）")
    void allowsOrderThatExactlyFillsCapacity() {
        when(departureMapper.selectById(7L)).thenReturn(departure(7L, 10, 6, 0, DepartureStatus.OPEN));
        when(departureMapper.update(any(), any())).thenReturn(1);
        stubInsertReturningId(56L);

        OrderView view = orderService.create(9L, request(7L, 4, 0, 4));

        assertEquals(new BigDecimal("4000.00"), view.totalAmount());
        verify(orderMapper).insert(any(TravelOrder.class));
    }

    // ---------------------------------------------------------------- 参数与状态校验

    @Test
    @DisplayName("团期已关闭时拒绝下单")
    void rejectsClosedDeparture() {
        when(departureMapper.selectById(7L)).thenReturn(departure(7L, 10, 0, 0, DepartureStatus.CLOSED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(9L, request(7L, 1, 0, 1)));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        verify(orderMapper, never()).insert(any(TravelOrder.class));
    }

    @Test
    @DisplayName("团期不存在时拒绝下单")
    void rejectsMissingDeparture() {
        when(departureMapper.selectById(7L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(9L, request(7L, 1, 0, 1)));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
    }

    @Test
    @DisplayName("成人与儿童数都为 0 时拒绝下单")
    void rejectsZeroParticipants() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(9L, request(7L, 0, 0, 0)));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }

    @Test
    @DisplayName("出行人数量与成人和儿童总数不一致时拒绝下单")
    void rejectsTravelerCountMismatch() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(9L, request(7L, 2, 1, 2)));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }
}
