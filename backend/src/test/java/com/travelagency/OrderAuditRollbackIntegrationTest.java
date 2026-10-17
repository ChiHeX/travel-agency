package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.domain.dto.CreateOrderRequest;
import com.travelagency.domain.dto.OrderView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.OrderTraveler;
import com.travelagency.domain.entity.Payment;
import com.travelagency.domain.entity.Refund;
import com.travelagency.domain.entity.Review;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.OperationLogMapper;
import com.travelagency.domain.mapper.OrderTravelerMapper;
import com.travelagency.domain.mapper.PaymentMapper;
import com.travelagency.domain.mapper.RefundMapper;
import com.travelagency.domain.mapper.ReviewMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计留痕与业务写入的<b>事务原子性</b>：审计写失败时，业务修改必须一起回滚。
 *
 * <p><b>为什么需要这个类</b>：B-04 把 {@code operation_log} 的写入从 Controller 移进了
 * Service 的业务事务（{@code OrderService.confirm} 先改订单状态 / 团期名额 / 线路统计，
 * 再写审计）。"同一个事务"这件事只有<b>真的让审计写入失败</b>才能证明 ——
 * 既有用例覆盖的都是"日志成功落库"，失败分支一条都没走。若两者其实不在同一个事务里，
 * 就会出现「接口报错、但订单已经变成已确认、名额也已经扣掉」这种对不上账的状态，
 * 而这正是审计必须与业务同生共死的原因。</p>
 *
 * <p><b>怎么让审计写入失败而不动 DDL</b>：{@code operation_log.operator_id} 有外键
 * {@code fk_operation_log_operator → sys_user(id)}。把操作人主键传成一个 {@code sys_user}
 * 里不存在的值，插入就必然失败 —— 用的是库自己的约束，不建触发器、不改表结构、
 * 也不 mock 掉 Recorder，因此走的仍是真实事务与真实连接。</p>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>（与 {@code DepartureCapacityConcurrencyIntegrationTest}
 * 同因）：测试自己的外层事务会把 service 的内层事务包进去，内层失败只把外层标记成
 * rollback-only，测试里读到的中间状态根本区分不出「回滚了」还是「没提交」。
 * 不加注解后每次调用都是真实、独立、自动提交的事务，回滚与否是库的真实行为。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class OrderAuditRollbackIntegrationTest {

    /** 团期容量：两组订单各占 2 个名额，留足余量。 */
    private static final int CAPACITY = 10;
    /** 订单人数：与下单时写入的出行人快照数量一致，保证业务复核能通过、走到审计写入那一步。 */
    private static final int PARTICIPANTS = 2;

    /**
     * 一个 {@code sys_user} 里必然不存在的主键，用来触发 {@code operation_log} 的外键失败。
     * 用 {@code BIGINT} 的上界而不是随机大数：随机值仍有理论上的碰撞可能，上界没有。
     */
    private static final long MISSING_OPERATOR_ID = Long.MAX_VALUE;

    @Autowired OrderService orderService;
    @Autowired DepartureMapper departures;
    @Autowired TravelOrderMapper orders;
    @Autowired PaymentMapper payments;
    @Autowired RefundMapper refunds;
    @Autowired OrderTravelerMapper orderTravelers;
    @Autowired MessageMapper messages;
    @Autowired ReviewMapper reviews;
    @Autowired TravelRouteMapper routes;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;
    @Autowired OperationLogMapper operationLogs;

    private Long routeId;
    private Long guideId;
    private Long guideUserId;
    private Long userId;
    private Long departureId;
    /** 合法操作人：对照组用它，也用来界定清理范围（留痕按 operator_id 一次删净）。 */
    private Long staffUserId;

    @BeforeEach
    void setUp() {
        TravelRoute route = new TravelRoute();
        route.name = "审计回滚回归线路";
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 1;
        route.status = "PUBLISHED";
        route.ratingAvg = new BigDecimal("0.00");
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        routeId = route.id;

        SysUser owner = new SysUser();
        owner.username = "audit_user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner.nickname = "审计回滚测试用户";
        owner.passwordHash = "unused-test-hash";
        owner.status = 1;
        owner.deleted = 0;
        users.insert(owner);
        userId = owner.id;

        SysUser guideOwner = new SysUser();
        guideOwner.username = "audit_guide_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        guideOwner.nickname = "审计回滚测试导游";
        guideOwner.passwordHash = "unused-test-hash";
        guideOwner.status = 1;
        guideOwner.deleted = 0;
        users.insert(guideOwner);
        guideUserId = guideOwner.id;

        Guide guide = new Guide();
        guide.userId = guideOwner.id;
        guide.name = "审计回滚测试导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        guideId = guide.id;

        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(20);
        departure.endDate = LocalDate.now().plusDays(22);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = CAPACITY;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 0;
        departure.guideId = guideId;
        departure.status = "OPEN";
        departure.version = 0;
        departures.insert(departure);
        departureId = departure.id;

        SysUser staff = new SysUser();
        staff.username = "audit_staff_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        staff.nickname = "审计回滚测试员工";
        staff.passwordHash = "unused-test-hash";
        staff.status = 1;
        staff.deleted = 0;
        users.insert(staff);
        staffUserId = staff.id;
    }

    @AfterEach
    void tearDown() {
        // 按外键依赖倒序清理：操作日志 → 订单子表 → 订单 → 站内信 → 团期 → 线路 → 导游 → 用户。
        // operation_log.operator_id 有指向 sys_user 的外键，不先删日志就删不掉操作人账号。
        if (staffUserId != null) {
            operationLogs.delete(new QueryWrapper<OperationLog>().eq("operator_id", staffUserId));
        }
        List<TravelOrder> mine = orders.selectList(new QueryWrapper<TravelOrder>().eq("departure_id", departureId));
        List<Long> orderIds = mine.stream().map(order -> order.id).toList();
        if (!orderIds.isEmpty()) {
            orderTravelers.delete(new QueryWrapper<OrderTraveler>().in("order_id", orderIds));
            payments.delete(new QueryWrapper<Payment>().in("order_id", orderIds));
            refunds.delete(new QueryWrapper<Refund>().in("order_id", orderIds));
            reviews.delete(new QueryWrapper<Review>().in("order_id", orderIds));
            orders.delete(new QueryWrapper<TravelOrder>().eq("departure_id", departureId));
        }
        if (userId != null) {
            messages.delete(new QueryWrapper<Message>().eq("user_id", userId));
        }
        if (departureId != null) {
            departures.deleteById(departureId);
        }
        if (routeId != null) {
            routes.deleteById(routeId);
        }
        if (guideId != null) {
            guides.deleteById(guideId);
        }
        if (userId != null) {
            users.deleteById(userId);
        }
        if (guideUserId != null) {
            users.deleteById(guideUserId);
        }
        if (staffUserId != null) {
            users.deleteById(staffUserId);
        }
    }

    /**
     * 审计写入失败 ⇒ 业务写入一起回滚。
     *
     * <p>顺序上是「先改订单状态 → 再迁团期名额 → 再回填线路统计 → <b>最后写审计</b>」，
     * 所以这条用例真正验证的是：<b>排在审计之前的每一步都被同一个事务带走了</b>。
     * 若审计与业务不在同一事务，这里会看到订单已经 {@code CONFIRMED}、名额已经被扣，
     * 但接口却是失败返回。</p>
     *
     * <p>没有只跑实验组：先跑一组<b>合法操作人</b>的对照。两者调用序列完全相同、只有操作人主键不同 ——
     * 对照组绿、实验组失败，才能说明红的是"审计写不进去"而不是"造数或前置条件本身有问题"。</p>
     */
    @Test
    @DisplayName("审计写入失败：订单状态、团期名额、线路统计、通知全部随事务回滚")
    void auditWriteFailureRollsBackBusinessChanges() {
        // ---------- 对照组：合法操作人，同一调用序列必须成功 ----------
        OrderView healthy = orderService.create(userId, request());
        orderService.markPaid(healthy.orderNo(), "ALI-AUDIT-ROLLBACK-OK", healthy.totalAmount());
        orderService.confirm(healthy.orderNo(), staffUserId);

        TravelOrder healthyOrder = orders.selectById(healthy.id());
        assertEquals(OrderStatus.CONFIRMED, healthyOrder.status,
                "对照组应确认成功 —— 否则后面的失败就说不清是不是别的原因");
        assertNotNull(healthyOrder.confirmedAt, "对照组必须写下 confirmed_at");
        assertEquals(1, countConfirmLogs(healthy.orderNo()), "对照组必须留下一条确认留痕");

        // ---------- 实验组：操作人主键不存在，审计插入撞外键 ----------
        OrderView failing = orderService.create(userId, request());
        orderService.markPaid(failing.orderNo(), "ALI-AUDIT-ROLLBACK-FAIL", failing.totalAmount());

        Departure departureBefore = departures.selectById(departureId);
        int routeCountBefore = routes.selectById(routeId).validBookingCount;

        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> orderService.confirm(failing.orderNo(), MISSING_OPERATOR_ID));

        assertTrue(rootMessage(failure).contains("fk_operation_log_operator"),
                "失败必须来自审计写入那条外键，而不是别的写入： " + rootMessage(failure));

        // 订单状态迁移必须被回滚
        TravelOrder reloaded = orders.selectById(failing.id());
        assertEquals(OrderStatus.PAID_WAIT_CONFIRM, reloaded.status,
                "审计写失败时订单不得停在已确认");
        assertNull(reloaded.confirmedAt, "confirmed_at 必须随事务回滚");

        // 团期名额与线路统计必须被回滚
        Departure departureAfter = departures.selectById(departureId);
        assertEquals(valueOrZero(departureBefore.reservedPeople), valueOrZero(departureAfter.reservedPeople),
                "reservedPeople 必须回滚（不得白减）");
        assertEquals(valueOrZero(departureBefore.confirmedPeople), valueOrZero(departureAfter.confirmedPeople),
                "confirmedPeople 必须回滚（不得白加）");
        assertEquals(routeCountBefore, routes.selectById(routeId).validBookingCount,
                "线路有效报名数必须回滚");

        // 审计与通知：不得留下无法与业务对上账的痕迹
        assertEquals(0, countConfirmLogs(failing.orderNo()), "回滚后不得留下孤立留痕");
        assertEquals(0, countMessagesMentioning(failing.orderNo()), "确认通知不得发出");

        System.out.println("[AUDIT-ROLLBACK] healthy=" + healthy.orderNo() + " -> CONFIRMED, log=1"
                + " | failing=" + failing.orderNo() + " -> " + reloaded.status
                + ", reserved=" + valueOrZero(departureAfter.reservedPeople)
                + ", confirmed=" + valueOrZero(departureAfter.confirmedPeople)
                + ", validBookingCount=" + routes.selectById(routeId).validBookingCount
                + ", confirmLogs=" + countConfirmLogs(failing.orderNo())
                + ", messages=" + countMessagesMentioning(failing.orderNo()));
    }

    /** 某张订单的确认留痕条数（按 object_id 定位，不受其它用例的写入影响）。 */
    private int countConfirmLogs(String orderNo) {
        return operationLogs.selectCount(new QueryWrapper<OperationLog>()
                .eq("object_type", "ORDER")
                .eq("operation_type", "CONFIRM")
                .eq("object_id", orderNo)).intValue();
    }

    /**
     * 提及该订单号的<b>确认</b>通知条数。
     *
     * <p>必须同时按 {@code type} 收窄：{@code markPaid} 自己就会发一条 {@code PAYMENT_SUCCESS}，
     * 它的正文里同样带着订单号 —— 只按内容匹配会数到那条支付通知，把这步测成永远为非 0。
     * 同理也不能只按 {@code type} 收窄：对照组会正常产生一条 {@code ORDER_CONFIRMED}。</p>
     */
    private int countMessagesMentioning(String orderNo) {
        return messages.selectCount(new QueryWrapper<Message>()
                .eq("user_id", userId)
                .eq("type", "ORDER_CONFIRMED")
                .like("content", orderNo)).intValue();
    }

    /** 取最内层原因的消息，用来断言到底是哪一条约束失败的。 */
    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? "" : cause.getMessage();
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private CreateOrderRequest request() {
        List<CreateOrderRequest.TravelerSnapshotRequest> travelers = new ArrayList<>();
        for (int i = 0; i < PARTICIPANTS; i++) {
            travelers.add(new CreateOrderRequest.TravelerSnapshotRequest(null, "审计回滚出行人" + i, "MALE",
                    LocalDate.of(1990, 1, 1), "CHINESE_ID_CARD", "31010119900101" + String.format("%04d", i),
                    "13800000000", "紧急联系人", "13900000000", "ADULT"));
        }
        return new CreateOrderRequest(departureId, PARTICIPANTS, 0, "审计回滚联系人", "13800000001",
                null, travelers, null);
    }
}
