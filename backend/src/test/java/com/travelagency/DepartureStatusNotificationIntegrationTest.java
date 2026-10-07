package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.OperationLogMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 团期状态变化通知（PRD §29）在真实库上的行为测试。
 *
 * <p>此前的覆盖只有 {@code DepartureAdminServiceTest} 里的一条 Mockito {@code verify}，
 * 证明"调用了通知方法"，但证明不了通知 SQL 本身对不对 ——
 * {@code MessageMapper#insertForDepartureParticipants} 是一条
 * {@code INSERT ... SELECT DISTINCT}，它的过滤条件（排除未支付 / 已取消 / 已退款）
 * 与去重语义只有真库才能验证。本类把这条 SQL 与两条触发路径（后台改状态、导游开始行程）
 * 都钉在真实 MySQL 上。</p>
 *
 * <p>同时覆盖"事务回滚不留下错误消息"：把一个会发消息的状态变更放进事务里并强制回滚，
 * 断言状态没变、消息也没留下。</p>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：回滚用例需要自己控制事务边界；
 * 夹具在 {@link #tearDown()} 里按标记删除（并顺带收掉上次中断留下的残留）。
 * 需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class DepartureStatusNotificationIntegrationTest {

    private static final String USER_PREFIX = "depnotif_";
    private static final String ROUTE_NAME = "团期通知回归线路";
    private static final String ORDER_PREFIX = "NOTIF-";

    @Autowired DepartureService departureService;
    @Autowired TravelRouteMapper routes;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;
    @Autowired DepartureMapper departures;
    @Autowired TravelOrderMapper orders;
    @Autowired MessageMapper messages;
    @Autowired OperationLogMapper operationLogs;
    @Autowired PlatformTransactionManager txManager;

    private Long routeId;
    private Long guideId;
    private Long adminUserId;
    private Long departureId;

    @BeforeEach
    void setUp() {
        TravelRoute route = new TravelRoute();
        route.name = ROUTE_NAME;
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

        SysUser guideUser = user();
        Guide guide = new Guide();
        guide.userId = guideUser.id;
        guide.name = "团期通知回归导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        guideId = guide.id;

        adminUserId = user().id;

        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = departures.databaseToday().plusDays(2);
        departure.endDate = departure.startDate.plusDays(5);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = 20;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 3;
        departure.guideId = guideId;
        departure.status = "OPEN";
        departure.version = 0;
        departures.insert(departure);
        departureId = departure.id;
    }

    @AfterEach
    void tearDown() {
        // 按标记清理，顺带收掉上次运行被中断时留下的残留。
        // 两个坑（与 DepartureStateConcurrencyIntegrationTest 相同）：
        //  1) MySQL 不允许 DELETE 的条件里自引用同一张表（错误 1093），sys_user / travel_order 只用自身列；
        //  2) 清理失败必须报出来 —— 吞掉异常会让"一条都没删"看起来像成功，残留就是这样攒起来的。
        String notifUsers = "SELECT id FROM sys_user WHERE username LIKE 'depnotif\\_%'";
        String notifRoutes = "SELECT id FROM travel_route WHERE name = '" + ROUTE_NAME + "'";
        List<Runnable> steps = List.of(
                () -> messages.delete(new QueryWrapper<Message>().inSql("user_id", notifUsers)),
                () -> orders.delete(new QueryWrapper<TravelOrder>().apply("order_no LIKE 'NOTIF-%'")),
                () -> departures.delete(new QueryWrapper<Departure>().inSql("route_id", notifRoutes)),
                () -> routes.delete(new QueryWrapper<TravelRoute>().eq("name", ROUTE_NAME)),
                () -> guides.delete(new QueryWrapper<Guide>().inSql("user_id", notifUsers)),
                () -> operationLogs.delete(new QueryWrapper<OperationLog>().inSql("operator_id", notifUsers)),
                () -> users.delete(new QueryWrapper<SysUser>().apply("username LIKE 'depnotif\\_%'")));

        List<RuntimeException> failures = new ArrayList<>();
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            RuntimeException first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    @Test
    @DisplayName("批量投递：只发给有效订单用户，同一用户多张有效订单只收一条")
    void insertForParticipantsFiltersAndDeduplicates() {
        Long confirmed = user().id;
        Long travelling = user().id;
        Long completed = user().id;
        Long waitPay = user().id;
        Long cancelled = user().id;
        Long refunded = user().id;

        order(confirmed, "CONFIRMED");
        order(confirmed, "COMPLETED");   // 同一用户第二张有效订单：必须被 DISTINCT 去掉
        order(travelling, "TRAVELLING");
        order(completed, "COMPLETED");
        order(waitPay, "WAIT_PAY");
        order(cancelled, "CANCELLED");
        order(refunded, "REFUNDED");

        int inserted = messages.insertForDepartureParticipants(
                departureId, "DEPARTURE_STATUS", "团期状态更新", "测试正文");

        assertEquals(3, inserted, "只应给 3 位有效订单用户各插一条（同一用户的多张订单去重）");
        assertEquals(1L, statusMessages(confirmed), "同一用户只应收到一条");
        assertEquals(1L, statusMessages(travelling));
        assertEquals(1L, statusMessages(completed));
        assertEquals(0L, statusMessages(waitPay), "未支付订单不应收到团期状态通知");
        assertEquals(0L, statusMessages(cancelled), "已取消订单不应收到团期状态通知");
        assertEquals(0L, statusMessages(refunded), "已退款订单不应收到团期状态通知");
    }

    @Test
    @DisplayName("后台改状态：通知有效订单用户并级联订单，重复提交同一状态不再通知")
    void changeStatusNotifiesOnceAndCascadesOrders() {
        Long confirmed = user().id;
        Long waitPay = user().id;
        Long confirmedOrderId = order(confirmed, "CONFIRMED");
        order(waitPay, "WAIT_PAY");

        departureService.changeStatus(departureId, "TRAVELLING", adminUserId);

        assertEquals(1L, statusMessages(confirmed), "有效订单用户应收到一条");
        assertEquals(0L, statusMessages(waitPay), "未支付订单不应收到");
        assertEquals("TRAVELLING", orders.selectById(confirmedOrderId).status, "订单应级联为在途");

        departureService.changeStatus(departureId, "TRAVELLING", adminUserId);
        assertEquals(1L, statusMessages(confirmed), "重复提交同一状态不应再通知");
    }

    @Test
    @DisplayName("导游开始行程：同样向有效订单用户投递通知")
    void guideStartNotifiesParticipants() {
        Long confirmed = user().id;
        order(confirmed, "CONFIRMED");

        departureService.start(departureId);

        assertEquals("TRAVELLING", departures.selectById(departureId).status);
        assertEquals(1L, statusMessages(confirmed), "导游开始行程应通知该团期有效订单用户");
    }

    @Test
    @DisplayName("事务回滚：状态没变，消息也不留")
    void rolledBackStatusChangeLeavesNoMessage() {
        Long confirmed = user().id;
        order(confirmed, "CONFIRMED");

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            departureService.changeStatus(departureId, "CANCELLED", adminUserId);
            status.setRollbackOnly();
        });

        assertEquals("OPEN", departures.selectById(departureId).status, "回滚后团期状态必须保持原样");
        assertEquals(0L, statusMessages(confirmed), "回滚后不应留下「团期状态更新」消息");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long statusMessages(Long userId) {
        Long count = messages.selectCount(new QueryWrapper<Message>()
                .eq("user_id", userId).eq("type", "DEPARTURE_STATUS"));
        return count == null ? 0L : count;
    }

    private Long order(Long userId, String status) {
        TravelOrder order = new TravelOrder();
        order.orderNo = ORDER_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        order.userId = userId;
        order.routeId = routeId;
        order.departureId = departureId;
        order.contactName = "团期通知联系人";
        order.contactPhone = "13800000001";
        order.adultCount = 1;
        order.childCount = 0;
        order.adultUnitPrice = new BigDecimal("2999.00");
        order.childUnitPrice = new BigDecimal("1999.00");
        order.totalAmount = new BigDecimal("2999.00");
        order.status = status;
        order.paymentStatus = "WAIT_PAY".equals(status) ? "UNPAID" : "PAID";
        orders.insert(order);
        return order.id;
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.username = USER_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.nickname = "团期通知用户";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }
}
