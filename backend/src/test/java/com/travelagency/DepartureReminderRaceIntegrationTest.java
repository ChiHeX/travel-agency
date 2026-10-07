package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.DepartureReminder;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.dto.UpcomingReminderTarget;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.DepartureReminderMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.OperationLogMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.service.DepartureReminderService;
import com.travelagency.domain.service.DepartureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 即将出发提醒的「候选查询 → 发送」竞态回归测试。
 *
 * <p>候选查询 {@code selectUpcomingReminderTargets} 是普通查询，读的是事务的<b>一致性快照</b>。
 * 从查出候选到真正写消息之间存在窗口：期间退款可能刚完成、团期可能刚被取消。
 * 旧实现直接按候选结果发送，于是给一笔已经退款的订单推了「即将出发」；唯一键只防重复，防不了这件事。
 * 现在每条候选在写入前都要经过 {@code lockEligibleReminderOrder} 的当前读复核，
 * 本类钉住这条复核确实生效。</p>
 *
 * <p><b>怎么把时序固定下来</b>：不靠 sleep，也不靠多线程撞运气，而是利用快照本身：</p>
 * <ol>
 *   <li>夹具先在独立事务里<b>提交</b>；</li>
 *   <li>在事务 T 里先跑一次候选查询 —— 快照就此钉在"状态变更之前"，并断言该订单确实在候选里
 *       （这是前置条件，说明后面的"跳过"不是候选查询过滤掉的，而是复核拦下的）；</li>
 *   <li>用 {@code REQUIRES_NEW}（独立连接、独立事务）提交状态变更，模拟并发退款/取消；</li>
 *   <li>回到 T 里跑提醒任务：候选查询仍读到旧的 {@code CONFIRMED}，复核是当前读、能看到新状态，
 *       于是跳过该订单、不写消息、也不写"已发送"标记。</li>
 * </ol>
 *
 * <p>全程在 T 里执行并最终回滚，因此不会往库里留下提醒记录或站内消息。</p>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：夹具必须真正提交，独立事务才改得动它；
 * 需要事务边界的两处（T 与 REQUIRES_NEW）都由测试自己用 {@code TransactionTemplate} 表达。
 * 夹具在 {@link #tearDown()} 里逐条删除，不留脏数据。需要数据库：
 * {@code $env:TRAVEL_MYSQL_TEST = "true"}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        "app.reminder.upcoming-days=3"})
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class DepartureReminderRaceIntegrationTest {

    private static final int UPCOMING_DAYS = 3;

    @Autowired DepartureReminderService reminderService;
    @Autowired DepartureService departureService;
    @Autowired TravelRouteMapper routes;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;
    @Autowired DepartureMapper departures;
    @Autowired TravelOrderMapper orders;
    @Autowired MessageMapper messages;
    @Autowired DepartureReminderMapper reminders;
    @Autowired OperationLogMapper operationLogs;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate inTransaction;
    private TransactionTemplate independentTransaction;

    private Long routeId;
    private Long guideId;
    private Long guideUserId;
    private Long adminUserId;
    private Long userId;
    private Long departureId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        // 夹具用普通（自动提交）写入并立即提交：只有提交后的行才能被独立事务修改。
        TravelRoute route = new TravelRoute();
        route.name = "提醒竞态线路";
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 6;
        route.status = "PUBLISHED";
        route.ratingAvg = new BigDecimal("0.00");
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        routeId = route.id;

        SysUser guideUser = user("race_guide");
        Guide guide = new Guide();
        guide.userId = guideUser.id;
        guide.name = "提醒竞态导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        guideId = guide.id;

        adminUserId = user("race_admin").id;
        userId = user("race_user").id;

        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(2);
        departure.endDate = departure.startDate.plusDays(5);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = 20;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 1;
        departure.guideId = guideId;
        departure.status = "OPEN";
        departure.version = 0;
        departures.insert(departure);
        departureId = departure.id;

        orderId = order("CONFIRMED").id;

        inTransaction = new TransactionTemplate(txManager);
        independentTransaction = new TransactionTemplate(txManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void tearDown() {
        messages.delete(new QueryWrapper<Message>().in("user_id", List.of(adminUserId, userId)));
        reminders.delete(new QueryWrapper<DepartureReminder>().eq("order_id", orderId));
        orders.deleteById(orderId);
        departures.deleteById(departureId);
        guides.deleteById(guideId);
        routes.deleteById(routeId);
        operationLogs.delete(new QueryWrapper<OperationLog>().eq("operator_id", adminUserId));
        users.delete(new QueryWrapper<SysUser>().in("id", List.of(adminUserId, userId, guideUserId)));
    }

    @Test
    @DisplayName("候选查出后若退款在同一批发送前完成，不再发送也不留标记")
    void skipsOrderRefundedAfterCandidatesWereRead() {
        runInTransactionWithRollback(() -> {
            assertOrderIsCandidate("前置条件：退款前该订单必须出现在候选里");

            // 另一个事务完成退款并提交 —— 对应 OrderService 退款审核通过后订单落成 REFUNDED。
            independentTransaction.executeWithoutResult(status ->
                    orders.update(null, new UpdateWrapper<TravelOrder>()
                            .eq("id", orderId)
                            .set("status", "REFUNDED")
                            .set("payment_status", "REFUNDED")));

            reminderService.sendUpcomingReminders();

            assertNoReminderForTheOrder("退款完成后不应再发送「即将出发」");
        });
    }

    @Test
    @DisplayName("候选查出后若团期被取消，不再发送也不留标记")
    void skipsOrderOfCancelledDeparture() {
        runInTransactionWithRollback(() -> {
            assertOrderIsCandidate("前置条件：取消前该订单必须出现在候选里");

            // 另一个事务把团期改成 CANCELLED 并提交 —— 走与后台完全相同的服务方法。
            independentTransaction.executeWithoutResult(status ->
                    departureService.changeStatus(departureId, "CANCELLED", adminUserId));

            reminderService.sendUpcomingReminders();

            assertNoReminderForTheOrder("团期取消后不应再发送「即将出发」");
        });
    }

    @Test
    @DisplayName("对照：期间没有任何状态变更时，提醒照常发送")
    void stillRemindsWhenNothingChanged() {
        runInTransactionWithRollback(() -> {
            assertOrderIsCandidate("前置条件：该订单必须出现在候选里");

            assertTrue(reminderService.sendUpcomingReminders() >= 1, "至少本订单应被提醒");
            assertNotNull(reminderOf(orderId), "应写入提醒发送记录");
            assertTrue(messageCount(userId) >= 1, "应写入站内消息");
        });
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 在事务 T 中执行，结束时整体回滚：提醒记录与站内消息都不会落库。 */
    private void runInTransactionWithRollback(Runnable body) {
        inTransaction.executeWithoutResult(status -> {
            try {
                body.run();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    /** 断言该订单确实出现在候选查询结果里，即这一步的快照仍然认为它符合出行条件。 */
    private void assertOrderIsCandidate(String message) {
        List<UpcomingReminderTarget> candidates = orders.selectUpcomingReminderTargets(UPCOMING_DAYS);
        assertTrue(candidates.stream().anyMatch(target -> target.orderId().equals(orderId)), message);
    }

    private void assertNoReminderForTheOrder(String message) {
        assertNull(reminderOf(orderId), message + "（不应留下发送记录）");
        assertTrue(messageCount(userId) == 0, message + "（不应写入站内消息）");
    }

    private DepartureReminder reminderOf(Long orderId) {
        return reminders.selectOne(new QueryWrapper<DepartureReminder>()
                .eq("order_id", orderId).eq("remind_type", DepartureReminderService.TYPE_UPCOMING));
    }

    private long messageCount(Long userId) {
        Long count = messages.selectCount(new QueryWrapper<Message>()
                .eq("user_id", userId).eq("type", DepartureReminderService.TYPE_UPCOMING));
        return count == null ? 0L : count;
    }

    private TravelOrder order(String status) {
        TravelOrder order = new TravelOrder();
        order.orderNo = "RC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        order.userId = userId;
        order.routeId = routeId;
        order.departureId = departureId;
        order.contactName = "提醒竞态联系人";
        order.contactPhone = "13800000001";
        order.adultCount = 1;
        order.childCount = 0;
        order.adultUnitPrice = new BigDecimal("2999.00");
        order.childUnitPrice = new BigDecimal("1999.00");
        order.totalAmount = new BigDecimal("2999.00");
        order.status = status;
        order.paymentStatus = "CONFIRMED".equals(status) ? "PAID" : "UNPAID";
        orders.insert(order);
        return order;
    }

    private SysUser user(String prefix) {
        SysUser user = new SysUser();
        user.username = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.nickname = "提醒竞态用户";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        if ("race_guide".equals(prefix)) {
            guideUserId = user.id;
        }
        return user;
    }
}
