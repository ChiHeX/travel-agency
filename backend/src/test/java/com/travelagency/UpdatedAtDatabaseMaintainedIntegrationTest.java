package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.domain.dto.CreateOrderRequest;
import com.travelagency.domain.dto.OrderView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.OrderTraveler;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.Payment;
import com.travelagency.domain.entity.Refund;
import com.travelagency.domain.entity.Review;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.OrderTravelerMapper;
import com.travelagency.domain.mapper.OperationLogMapper;
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
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code created_at} / {@code updated_at} 必须由数据库维护，业务更新后 {@code updated_at} 要真的前进。
 *
 * <p><b>为什么需要这个类</b>：{@code BaseEntity} 的 {@code updatedAt} 没有填充注解，实体又是从库里
 * 读出来的 —— 它持有的是<b>旧值</b>。MyBatis-Plus {@code updateById} 默认按 NOT_NULL 策略把这个旧值
 * 写进 SET，于是压掉了列定义上的 {@code ON UPDATE CURRENT_TIMESTAMP}，{@code updated_at} 永远停在
 * 创建时间。这不是内部卫生问题：{@code updatedAt} 在 {@code docs/openapi.yaml} 里是<b>必返字段</b>
 * （订单、团期、酒店、线路、文章、出行人…），前端拿它当"最后更新时间"展示，冻住即等于给前端一个错值。
 *
 * <p>最坏的一条路径是支付回调：{@code markPaid} 先用条件 UPDATE 原子地把支付单推进到 PAID
 * （这一步 {@code ON UPDATE} 生效、时间戳前进），紧接着又 {@code updateById} 把实体里的旧值写回去，
 * 等于<b>前进之后又倒退</b>。第三个用例专门盯这条路径，而且断言的是<b>支付单自己</b>那一列 ——
 * 只看订单时间戳看不到这一幕。
 *
 * <p><b>两个层面都要断言</b>：{@code FieldStrategy.NEVER} 只解决"库不被旧值压掉"，它管不到
 * "写操作的响应里装的是什么"。更新之后内存实体仍持有旧时间戳，若把它直接交给
 * {@code OrderView.from(...)}，<b>库是对的、响应是旧的</b>。所以取消与确认两个用例除了查库，
 * 还要断言接口返回值里的 {@code updatedAt} 与库一致 —— 这正是只查库会漏掉的那一半。
 *
 * <p><b>为什么要 sleep</b>：{@code DATETIME} 没有小数位，插入与更新若落在同一秒，即使
 * {@code updated_at} 从未前进，两次读到的值也相等 —— 不断言出问题就成了假绿。所以两次读之间
 * 必须跨过秒边界。这是本类唯一一处等待，不是凑时间。
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class UpdatedAtDatabaseMaintainedIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int PARTICIPANTS = 2;

    /** 跨秒边界所需的等待：1.1s 足以保证下一次写入落在不同的秒。 */
    private static final long CROSS_SECOND_MILLIS = 1_100L;

    @Autowired OrderService orderService;
    @Autowired DepartureMapper departures;
    @Autowired TravelOrderMapper orders;
    @Autowired PaymentMapper payments;
    @Autowired RefundMapper refunds;
    @Autowired OrderTravelerMapper orderTravelers;
    @Autowired OperationLogMapper operationLogs;
    @Autowired MessageMapper messages;
    @Autowired ReviewMapper reviews;
    @Autowired TravelRouteMapper routes;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;

    private Long routeId;
    private Long guideId;
    private Long guideUserId;
    private Long userId;
    private Long departureId;

    @BeforeEach
    void setUp() {
        TravelRoute route = new TravelRoute();
        route.name = "更新时间回归线路";
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
        owner.username = "upd_user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner.nickname = "更新时间测试用户";
        owner.passwordHash = "unused-test-hash";
        owner.status = 1;
        owner.deleted = 0;
        users.insert(owner);
        userId = owner.id;

        SysUser guideOwner = new SysUser();
        guideOwner.username = "upd_guide_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        guideOwner.nickname = "更新时间测试导游";
        guideOwner.passwordHash = "unused-test-hash";
        guideOwner.status = 1;
        guideOwner.deleted = 0;
        users.insert(guideOwner);
        guideUserId = guideOwner.id;

        Guide guide = new Guide();
        guide.userId = guideOwner.id;
        guide.name = "更新时间测试导游";
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
    }

    @AfterEach
    void tearDown() {
        // 按外键依赖倒序清理（与 DepartureCapacityConcurrencyIntegrationTest 同口径）。
        List<TravelOrder> mine = orders.selectList(
                new QueryWrapper<TravelOrder>().eq("departure_id", departureId));
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
        // 确认报名会以 userId 作为操作人写一条 operation_log，而 fk_operation_log_operator
        // 指向 sys_user：不清掉它，下面删测试用户就会撞外键、把本类拖红。
        // 清理必须排在 sys_user 之前，口径与 OrderAuditRollbackIntegrationTest 一致。
        if (userId != null || guideUserId != null) {
            List<Long> operatorIds = new ArrayList<>();
            if (userId != null) {
                operatorIds.add(userId);
            }
            if (guideUserId != null) {
                operatorIds.add(guideUserId);
            }
            operationLogs.delete(new QueryWrapper<OperationLog>().in("operator_id", operatorIds));
        }
        if (userId != null) {
            users.deleteById(userId);
        }
        if (guideUserId != null) {
            users.deleteById(guideUserId);
        }
    }

    @Test
    @DisplayName("用户取消订单后 updated_at 由数据库推进，而不是停在创建时间")
    void cancelAdvancesUpdatedAt() throws InterruptedException {
        OrderView order = orderService.create(userId, request());
        Long orderId = order.id();

        LocalDateTime createdAt = readCreatedAt(orderId);
        LocalDateTime before = readUpdatedAt(orderId);
        assertNotNull(before, "插入后 updated_at 必须由 DEFAULT CURRENT_TIMESTAMP 填上");
        assertEquals(createdAt, before, "刚插入时两列同源，应相等");

        Thread.sleep(CROSS_SECOND_MILLIS);
        OrderView cancelled = orderService.cancel(order.orderNo(), userId);

        LocalDateTime after = readUpdatedAt(orderId);
        assertTrue(after.isAfter(before),
                "取消是一次业务更新，updated_at 必须前进：before=" + before + " after=" + after);
        // 光看库不够：FieldStrategy.NEVER 只保证「库不被旧值压掉」，管不到「响应里装的是什么」。
        // 取消接口直接返回 OrderView，若把更新前读到的实体原样交给 OrderView.from(...)，
        // 库是对的、响应却是旧 updatedAt —— 前端得再查一次才看到新值。
        assertEquals(after, cancelled.updatedAt(),
                "取消接口的响应必须带回库里的最新 updatedAt，不能是更新前的旧值");
    }

    @Test
    @DisplayName("后台确认报名后 updated_at 前进，且确认接口的响应带回最新值")
    void confirmAdvancesUpdatedAt() throws InterruptedException {
        OrderView order = orderService.create(userId, request());
        Long orderId = order.id();
        orderService.markPaid(order.orderNo(), "ALI-CONFIRM-UPDATED-AT-TEST", order.totalAmount());

        LocalDateTime before = readUpdatedAt(orderId);

        Thread.sleep(CROSS_SECOND_MILLIS);
        OrderView confirmed = orderService.confirm(order.orderNo(), userId);

        LocalDateTime after = readUpdatedAt(orderId);
        assertTrue(after.isAfter(before),
                "确认报名是一次业务更新，updated_at 必须前进：before=" + before + " after=" + after);
        assertEquals(after, confirmed.updatedAt(),
                "确认接口的响应必须带回库里的最新 updatedAt，不能是更新前的旧值");
    }

    @Test
    @DisplayName("支付回调入账后 updated_at 必须前进 —— 原子闸门推进过的时间戳不能被写回旧值")
    void markPaidAdvancesUpdatedAt() throws InterruptedException {
        OrderView order = orderService.create(userId, request());
        Long orderId = order.id();

        LocalDateTime orderBefore = readUpdatedAt(orderId);
        LocalDateTime paymentBefore = readPayment(orderId).updatedAt;
        assertNotNull(paymentBefore, "插入后支付单 updated_at 也必须由 DEFAULT CURRENT_TIMESTAMP 填上");

        Thread.sleep(CROSS_SECOND_MILLIS);
        orderService.markPaid(order.orderNo(), "ALI-UPDATED-AT-TEST", order.totalAmount());

        LocalDateTime orderAfter = readUpdatedAt(orderId);
        assertTrue(orderAfter.isAfter(orderBefore),
                "回调入账是一次业务更新，订单 updated_at 必须前进：before=" + orderBefore
                        + " after=" + orderAfter);

        // 支付单才是这条链路的「最坏路径」现场：markPaid 先用条件 UPDATE 把支付单推进到 PAID
        // （此刻 ON UPDATE 生效、时间戳前进），紧接着的 updateById 在修复前会把实体里的旧值写回去，
        // 等于前进后又倒退。只断言订单时间戳看不到这一幕，必须单独盯支付单自己那一列。
        Payment paymentAfter = readPayment(orderId);
        assertEquals(PaymentStatus.PAID, paymentAfter.status, "回调后支付单必须落 PAID");
        assertTrue(paymentAfter.updatedAt.isAfter(paymentBefore),
                "回调入账后支付单 updated_at 必须前进，不能被 updateById 写回旧值：before="
                        + paymentBefore + " after=" + paymentAfter.updatedAt);
    }

    /** 每次调用都是一次新的查询，拿到的才是库里的当前值。 */
    private LocalDateTime readUpdatedAt(Long orderId) {
        return orders.selectById(orderId).updatedAt;
    }

    /** 支付单同理：读库而不是复用内存对象，否则断言就变成"自己跟自己比"。 */
    private Payment readPayment(Long orderId) {
        Payment payment = payments.selectOne(new QueryWrapper<Payment>().eq("order_id", orderId));
        assertNotNull(payment, "订单创建时必须同时生成支付单");
        return payment;
    }

    private LocalDateTime readCreatedAt(Long orderId) {
        return orders.selectById(orderId).createdAt;
    }

    private CreateOrderRequest request() {
        List<CreateOrderRequest.TravelerSnapshotRequest> travelers = new ArrayList<>();
        for (int i = 0; i < PARTICIPANTS; i++) {
            travelers.add(new CreateOrderRequest.TravelerSnapshotRequest(null, "更新时间出行人" + i, "MALE",
                    LocalDate.of(1990, 1, 1), "CHINESE_ID_CARD", "31010119900101" + String.format("%04d", i),
                    "13800000000", "紧急联系人", "13900000000", "ADULT"));
        }
        return new CreateOrderRequest(departureId, PARTICIPANTS, 0, "更新时间联系人", "13800000001",
                null, travelers, null);
    }
}
