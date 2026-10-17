package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.common.alipay.AlipayGatewayClient;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.exception.OrderAuditAnomalyException;
import com.travelagency.domain.dto.CreateOrderRequest;
import com.travelagency.domain.dto.OrderView;
import com.travelagency.domain.dto.RefundRequest;
import com.travelagency.domain.dto.RefundView;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 交易域并发实测：名额守恒、并发确认、并发退款审核，以及审核异常的事务提交语义。
 *
 * <p><b>为什么需要这个类</b>：这些闸门都是 SQL 语句
 * （占名额用 {@code ... + N <= max_people} 作为 WHERE 条件，确认报名用
 * {@code WHERE status = PAID_WAIT_CONFIRM} 的条件 UPDATE），逻辑上原子，
 * 但<b>没有任何实测数据</b>就无法证明并发下真的只生效一次。本类用真实 MySQL + 真实线程池
 * 把闸门压到竞争状态，把结论从"看代码应该没问题"变成数据。</p>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：测试事务会把所有并发请求并进同一个连接与事务，
 * 并发退化成顺序执行、测不出任何东西。它同时是本类能验证
 * {@code @Transactional(noRollbackFor = OrderAuditAnomalyException.class)} 的唯一方式 ——
 * 在测试事务里内层永远不会真正提交，"审核异常是否落库"根本无从断言。
 * 因此本类自行造数据，并在结束后按外键倒序逐条删除。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class DepartureCapacityConcurrencyIntegrationTest {

    /** 团期容量：并发请求数远大于它，闸门必须真的拦下超额部分。 */
    private static final int CAPACITY = 10;
    /** 并发下单请求数。 */
    private static final int CONCURRENT_ORDERS = 30;
    /** 并发审批线程数。 */
    private static final int THREADS = 8;
    /** 并发确认报名线程数：远大于 1，用来暴露"同一订单被确认两次"。 */
    private static final int CONFIRM_THREADS = 8;

    /** 测试用 APPID，只用于通过出款配置齐全性校验，不是真实沙箱账号。 */
    private static final String TEST_APP_ID = "9021000168641134";
    /** 现场生成的测试密钥对：不落盘、不提交、不联网。 */
    private static final KeyPair APP_KEY_PAIR = keyPair();

    /**
     * 注入一份<b>齐全</b>的支付宝出款配置。
     *
     * <p>退款审核接入真实出款后会先过出款闸门（缺 APPID / 应用私钥即 409
     * {@code REFUND_NOT_CONFIGURED}）。本类要测的是<b>并发审批闸门与名额守恒</b>，
     * 前提必须是「配置没问题、能走到出款那一步」；否则所有线程都会在配置闸门处被拒，
     * 跑出来的红是配置缺失而不是并发缺陷，等于没测。</p>
     *
     * <p>用动态属性而不是注解字面量，是因为密钥要现场生成、而注解只能写编译期常量。</p>
     */
    @DynamicPropertySource
    static void alipayCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.integrations.alipay.app-id", () -> TEST_APP_ID);
        registry.add("app.integrations.alipay.app-private-key",
                () -> base64(APP_KEY_PAIR.getPrivate().getEncoded()));
    }

    /**
     * 支付宝适配器的 spy：<b>只替换出款那一跳</b>。
     *
     * <p>退款是同步接口，走真实现就会真的出网；本环境既没有对应的支付宝交易、也不允许出网，
     * 所以 {@code refund(...)} 必须被替换成成功响应（见 {@code setUp}），其余方法保持真实。</p>
     */
    @MockitoSpyBean AlipayGatewayClient alipayGatewayClient;

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
    /** 专门为本类创建的操作人账号：审核留痕挂在它名下，清理时按 operator_id 一次删净。 */
    private Long staffUserId;

    @BeforeEach
    void setUp() {
        TravelRoute route = new TravelRoute();
        route.name = "名额守恒回归线路";
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
        owner.username = "cap_user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner.nickname = "名额守恒测试用户";
        owner.passwordHash = "unused-test-hash";
        owner.status = 1;
        owner.deleted = 0;
        users.insert(owner);
        userId = owner.id;

        SysUser guideOwner = new SysUser();
        guideOwner.username = "cap_guide_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        guideOwner.nickname = "名额守恒测试导游";
        guideOwner.passwordHash = "unused-test-hash";
        guideOwner.status = 1;
        guideOwner.deleted = 0;
        users.insert(guideOwner);

        Guide guide = new Guide();
        guide.userId = guideOwner.id;
        guide.name = "名额守恒测试导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        guideId = guide.id;
        guideUserId = guideOwner.id;

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
        staff.username = "cap_staff_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        staff.nickname = "名额守恒测试员工";
        staff.passwordHash = "unused-test-hash";
        staff.status = 1;
        staff.deleted = 0;
        users.insert(staff);
        staffUserId = staff.id;

        // 「出款这一跳」固定为成功：本类不测支付宝，只测并发审批闸门与名额守恒。
        // 用 doAnswer(...).when(spy) 而【不是】when(spy.refund(...)) —— 后者在打桩阶段
        // 就会真调一次被测方法，在这个类里等于当场发起一次真实网络请求。
        // 商户订单号原样回填，保证返回值形状与真实响应一致。
        doAnswer(invocation -> AlipayGatewayClient.RefundResult.succeeded(
                "2027030122001400000000000001", invocation.getArgument(0)))
                .when(alipayGatewayClient)
                .refund(anyString(), anyString(), any(BigDecimal.class), anyString());
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

    // ------------------------------------------------------------------
    // 名额守恒
    // ------------------------------------------------------------------

    /**
     * 30 个并发请求抢 10 个名额：恰好 10 单成功、20 单被 409 拦下，
     * 且库里的占用名额、订单数、支付单数全都等于容量。
     */
    @Test
    @DisplayName("名额守恒：并发抢同一声明名额，成功单数恰好等于容量，占用不超上限")
    void concurrentOrdersNeverExceedDepartureCapacity() throws Exception {
        RaceResult result = race(CONCURRENT_ORDERS, () -> orderService.create(userId, request(1)));

        Departure departure = departures.selectById(departureId);
        int reserved = valueOrZero(departure.reservedPeople);
        int confirmed = valueOrZero(departure.confirmedPeople);
        int max = valueOrZero(departure.maxPeople);

        System.out.println("[CAPACITY-STRESS] requests=" + CONCURRENT_ORDERS + " capacity=" + CAPACITY
                + " succeeded=" + result.succeeded() + " rejected=" + result.conflicts().size()
                + " dbReserved=" + reserved + " dbConfirmed=" + confirmed + " dbMax=" + max);

        assertEquals(CAPACITY, result.succeeded(), "成功下单数必须恰好等于团期容量");
        assertEquals(CONCURRENT_ORDERS - CAPACITY, result.conflicts().size(), "其余请求都应在名额闸门处被拒绝");
        for (BusinessException failure : result.conflicts()) {
            assertEquals(409, failure.getStatus(), "名额不足必须是 409");
            assertEquals("DEPARTURE_CAPACITY_INSUFFICIENT", failure.getCode(), "必须回契约错误码");
        }
        assertEquals(CAPACITY, reserved, "已占名额必须恰好等于容量");
        assertTrue(reserved + confirmed <= max,
                "占用名额不得超过上限：reserved=" + reserved + " confirmed=" + confirmed + " max=" + max);
        assertEquals(CAPACITY, countOrders(), "不得产生超额订单");
        assertEquals(CAPACITY, countPayments(), "每张订单恰好一张支付单");

        // 并发结束之后闸门依然生效：再来一单必须被拒
        BusinessException afterRace = expectConflict(() -> orderService.create(userId, request(1)));
        assertEquals("DEPARTURE_CAPACITY_INSUFFICIENT", afterRace.getCode(), "名额用尽后仍应拒绝新订单");
        assertEquals(CAPACITY, departures.selectById(departureId).reservedPeople, "被拒的请求不得改动占用名额");
    }

    /**
     * 单车超容量：一次请求要的位数超过剩余名额时，闸门同样要拦下，且不得留下半张订单。
     */
    @Test
    @DisplayName("名额守恒：单车位数超过剩余名额时整单拒绝，不留下半个订单")
    void oversizeSingleRequestIsRejectedAtomically() {
        // 先把 9 个名额占掉（留 1 个），再用一单 2 人去撞
        orderService.create(userId, request(1));
        orderService.create(userId, request(4));
        orderService.create(userId, request(4));

        assertEquals(9, departures.selectById(departureId).reservedPeople, "前置占用应为 9");

        BusinessException rejected = expectConflict(() -> orderService.create(userId, request(2)));

        assertEquals("DEPARTURE_CAPACITY_INSUFFICIENT", rejected.getCode());
        assertEquals(9, departures.selectById(departureId).reservedPeople, "被拒的整单不得占用任何名额");
        assertEquals(3, countOrders(), "被拒的订单不得落库");
        assertEquals(3, countPayments(), "被拒的订单不得产生支付单");
    }

    // ------------------------------------------------------------------
    // 退款审核并发
    // ------------------------------------------------------------------

    /**
     * 8 个审核人同时通过同一张退款单：只有一个能抢到 APPLYING→PROCESSING 的原子闸门，
     * 名额与线路有效报名数都只回退一次。
     */
    @Test
    @DisplayName("并发审批：同一张退款单只有一个审核人成功，名额与有效报名数只回退一次")
    void concurrentRefundApprovalsReleaseCapacityExactlyOnce() throws Exception {
        OrderView order = orderService.create(userId, request(2));
        orderService.markPaid(order.orderNo(), "ALI-TRADE-CAP-RACE", order.totalAmount());
        orderService.confirm(order.orderNo(), staffUserId);

        assertEquals("CONFIRMED", orders.selectById(order.id()).status, "前置：订单应为已确认");
        Departure before = departures.selectById(departureId);
        assertEquals(2, before.confirmedPeople, "前置：2 个名额应已从 reserved 转入 confirmed");
        assertEquals(0, before.reservedPeople, "前置：reserved 应被 confirm 清空");

        RefundView refund = orderService.applyRefund(order.orderNo(), userId,
                new RefundRequest("并发审核回归用例：出行计划变更"));
        assertEquals("CONFIRMED", refund.originalOrderStatus(), "前置：退款单应记住原订单状态（决定名额往哪张表回退）");
        assertEquals("APPLYING", refund.status(), "前置：退款单应处于待审核");

        // 走真实入口 approveRefund（事务边界与 noRollbackFor 都挂在它上面），
        // 而不是内部分支 processRefund —— 后者不是事务入口，直调等于换了一条链路在测。
        RaceResult result = race(THREADS,
                () -> orderService.approveRefund(Long.valueOf(refund.id()), "并发审核通过", staffUserId));

        Departure after = departures.selectById(departureId);
        System.out.println("[REFUND-RACE] threads=" + THREADS + " succeeded=" + result.succeeded()
                + " rejected=" + result.conflicts().size() + " dbReserved=" + valueOrZero(after.reservedPeople)
                + " dbConfirmed=" + valueOrZero(after.confirmedPeople)
                + " validBookingCount=" + routes.selectById(routeId).validBookingCount);

        assertEquals(1, result.succeeded(), "并发审批同一张退款单只允许一个审核人成功");
        assertEquals(THREADS - 1, result.conflicts().size(), "其余审核人应在原子闸门处被拒绝");
        for (BusinessException failure : result.conflicts()) {
            assertEquals(409, failure.getStatus(), "重复审批必须是 409");
            assertEquals("REFUND_STATE_CONFLICT", failure.getCode(), "必须回契约错误码");
        }
        assertEquals(0, valueOrZero(after.confirmedPeople), "名额只回退一次");
        assertEquals(0, valueOrZero(after.reservedPeople), "reserved 不应出现负数");
        assertEquals(0, routes.selectById(routeId).validBookingCount, "线路有效报名数只回退一次");
        assertEquals("REFUNDED", refunds.selectById(Long.valueOf(refund.id())).status, "退款单终态为 REFUNDED");
        TravelOrder reloaded = orders.selectById(order.id());
        assertEquals("REFUNDED", reloaded.status, "订单应为已退款");
        assertEquals("REFUNDED", reloaded.paymentStatus, "支付单状态应为已退款");
        assertNotNull(reloaded.updatedAt, "订单应被写过");
    }

    // ------------------------------------------------------------------
    // 并发确认报名
    // ------------------------------------------------------------------

    /**
     * 8 个工作人员同时确认同一张订单。
     *
     * <p>旧实现是"普通读订单 → 判断状态 → 更新人数与统计 → 发通知"，
     * 两个线程都能读到 {@code PAID_WAIT_CONFIRM}，于是名额被迁两次、有效报名数被加两次、
     * 用户收到两条通知。现在状态迁移走条件 UPDATE 闸门，只有一行被影响的那次才继续做副作用。</p>
     */
    @Test
    @DisplayName("并发确认：同一订单只确认一次，名额只迁移一次、统计只加一次、通知只发一条")
    void concurrentConfirmsAdvanceTheOrderExactlyOnce() throws Exception {
        OrderView order = orderService.create(userId, request(2));
        orderService.markPaid(order.orderNo(), "ALI-TRADE-CONFIRM-RACE", order.totalAmount());
        int confirmedMessagesBefore = countMessages("ORDER_CONFIRMED");

        RaceResult result = race(CONFIRM_THREADS,
                () -> orderService.confirm(order.orderNo(), staffUserId));

        Departure departure = departures.selectById(departureId);
        System.out.println("[CONFIRM-RACE] threads=" + CONFIRM_THREADS + " succeeded=" + result.succeeded()
                + " rejected=" + result.conflicts().size()
                + " dbReserved=" + valueOrZero(departure.reservedPeople)
                + " dbConfirmed=" + valueOrZero(departure.confirmedPeople)
                + " validBookingCount=" + routes.selectById(routeId).validBookingCount
                + " confirmLogs=" + countOperationLogs("CONFIRM"));

        assertEquals(1, result.succeeded(), "同一张订单只允许确认一次");
        assertEquals(CONFIRM_THREADS - 1, result.conflicts().size(), "其余线程应在状态迁移闸门处被拒");
        for (BusinessException failure : result.conflicts()) {
            assertEquals(409, failure.getStatus(), "重复确认必须是 409");
            assertEquals("ORDER_STATE_CONFLICT", failure.getCode(), "必须回契约错误码");
        }
        assertEquals(2, valueOrZero(departure.confirmedPeople), "2 个名额只从 reserved 转入 confirmed 一次");
        assertEquals(0, valueOrZero(departure.reservedPeople), "reserved 不得被减成负数");
        assertEquals(1, routes.selectById(routeId).validBookingCount, "线路有效报名数只加一次");
        assertEquals(confirmedMessagesBefore + 1, countMessages("ORDER_CONFIRMED"), "确认通知只发一条");
        assertEquals("CONFIRMED", orders.selectById(order.id()).status, "订单终态为已确认");
        assertEquals(1, countOperationLogs("CONFIRM"), "确认留痕恰好一条");
    }

    /**
     * 审核异常的事务语义：<b>业务数据回滚、审计留痕与站内通知提交</b>。
     *
     * <p>这是 {@code @Transactional(noRollbackFor = OrderAuditAnomalyException.class)} 的唯一可验证点，
     * 也是本类不加 {@code @Transactional} 的主要原因 —— 在测试事务里内层永远不会真正提交。</p>
     */
    @Test
    @DisplayName("审核异常：订单保持待确认且不动名额统计，但审计留痕与站内通知确实提交")
    void auditAnomalyKeepsOrderPendingWhileCommittingLogAndNotification() {
        OrderView order = orderService.create(userId, request(2));
        orderService.markPaid(order.orderNo(), "ALI-TRADE-ANOMALY", order.totalAmount());
        // 把快照的证件号清空，模拟存量脏数据 / 导入缺失：这不是 create 会产出的形状，
        // 但确认报名必须能把这类订单挡下来。
        orderTravelers.update(null, new UpdateWrapper<OrderTraveler>()
                .eq("order_id", order.id()).set("id_no", ""));

        Departure before = departures.selectById(departureId);

        OrderAuditAnomalyException ex = assertThrows(OrderAuditAnomalyException.class,
                () -> orderService.confirm(order.orderNo(), staffUserId));

        assertEquals(OrderAuditAnomalyException.TRAVELER_IDENTITY_INCOMPLETE, ex.reasonCode());
        assertEquals(409, ex.getStatus(), "审核异常回 409");
        assertFalse(ex.getMessage().contains("310101"), "接口 message 不得泄露证件号");

        TravelOrder reloaded = orders.selectById(order.id());
        assertEquals("PAID_WAIT_CONFIRM", reloaded.status, "异常订单不得进入 CONFIRMED");
        Departure after = departures.selectById(departureId);
        assertEquals(valueOrZero(before.reservedPeople), valueOrZero(after.reservedPeople), "异常不得改动占用名额");
        assertEquals(valueOrZero(before.confirmedPeople), valueOrZero(after.confirmedPeople), "异常不得改动已确认人数");
        assertEquals(0, routes.selectById(routeId).validBookingCount, "异常不得回填线路统计");

        OperationLog log = latestAnomalyLog();
        assertEquals("FAILURE", log.result, "审核异常必须以 FAILURE 留痕");
        assertTrue(log.detail.contains("实名"), "留痕应说明异常原因： " + log.detail);
        // 同一异常重复复核不能再次投递相同提醒。
        assertThrows(OrderAuditAnomalyException.class,
                () -> orderService.confirm(order.orderNo(), staffUserId));
        List<Message> notifications = messages.selectList(new QueryWrapper<Message>()
                .eq("user_id", userId).eq("type", "ORDER_AUDIT_ANOMALY"));
        assertEquals(1, notifications.size(), "必须留下一条审核异常通知（noRollbackFor 使其不被回滚）");
        assertFalse(notifications.get(0).content.contains("310101"), "通知正文不得泄露证件号");
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    /** 用 {@link CountDownLatch} 把 N 个线程同时放行，收集成功次数与被业务闸门拦下的异常。 */
    private RaceResult race(int threads, Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BusinessException>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                await(go);
                try {
                    action.run();
                    return null;
                } catch (BusinessException failure) {
                    return failure;
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未能全部就绪");
        go.countDown();

        List<BusinessException> conflicts = new ArrayList<>();
        int succeeded = 0;
        try {
            for (Future<BusinessException> future : futures) {
                BusinessException failure = future.get(60, TimeUnit.SECONDS);
                if (failure == null) {
                    succeeded++;
                } else {
                    conflicts.add(failure);
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
        return new RaceResult(succeeded, conflicts);
    }

    /** 顺序调用一次，断言抛出业务异常并把它返回给调用方做错误码断言。 */
    private BusinessException expectConflict(Runnable action) {
        try {
            action.run();
        } catch (BusinessException failure) {
            return failure;
        }
        throw new AssertionError("预期因名额不足被拒绝，但调用成功了");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int countOrders() {
        return orders.selectCount(new QueryWrapper<TravelOrder>().eq("departure_id", departureId)).intValue();
    }

    private int countPayments() {
        return payments.selectCount(new QueryWrapper<Payment>()
                .inSql("order_id", "SELECT id FROM travel_order WHERE departure_id = " + departureId)).intValue();
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 本测试用户收到的某一类站内信条数。 */
    private int countMessages(String type) {
        return messages.selectCount(new QueryWrapper<Message>()
                .eq("user_id", userId).eq("type", type)).intValue();
    }

    /** 本测试操作人写下的某一类审计留痕条数。 */
    private int countOperationLogs(String operationType) {
        return operationLogs.selectCount(new QueryWrapper<OperationLog>()
                .eq("operator_id", staffUserId).eq("operation_type", operationType)).intValue();
    }

    /** 取本测试操作人最近一条报名审核异常留痕，没有则直接失败。 */
    private OperationLog latestAnomalyLog() {
        List<OperationLog> logs = operationLogs.selectList(new QueryWrapper<OperationLog>()
                .eq("operator_id", staffUserId).eq("operation_type", "AUDIT_ANOMALY")
                .orderByDesc("id").last("LIMIT 1"));
        assertEquals(1, logs.size(), "审核异常应恰好留下一条留痕");
        return logs.get(0);
    }

    /** 现场生成的 2048 位 RSA 密钥对，仅用于让「出款配置齐全」判据成立：不出网、不落盘。 */
    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64(byte[] der) {
        return Base64.getEncoder().encodeToString(der);
    }

    private CreateOrderRequest request(int adults) {
        List<CreateOrderRequest.TravelerSnapshotRequest> travelers = new ArrayList<>();
        for (int i = 0; i < adults; i++) {
            travelers.add(new CreateOrderRequest.TravelerSnapshotRequest(null, "并发测试出行人" + i, "MALE",
                    LocalDate.of(1990, 1, 1), "CHINESE_ID_CARD", "31010119900101" + String.format("%04d", i),
                    "13800000000", "紧急联系人", "13900000000", "ADULT"));
        }
        return new CreateOrderRequest(departureId, adults, 0, "并发测试联系人", "13800000001", null, travelers, null);
    }

    private record RaceResult(int succeeded, List<BusinessException> conflicts) {
    }
}
