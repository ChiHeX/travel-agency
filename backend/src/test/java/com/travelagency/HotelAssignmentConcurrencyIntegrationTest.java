package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.enums.AccommodationType;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.ItineraryDayRequest;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.OperationLogMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.service.AdminRouteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「停用酒店不能再被安排进新行程」这条规则的并发交错实测。
 *
 * <p><b>为什么需要这个类</b>：规则本身只是"读一次酒店状态、再写行程"，两次操作之间有一道缝。
 * 检测用的普通查询读的是本事务的一致性快照，而这个快照在事务第一次读（{@code requireRoute}）时
 * 就已经建立：酒店如果在"读状态"与"写行程"之间被另一位工作人员停用并提交，
 * 快照里它仍然是 {@code ACTIVE} —— 校验通过、行程落库，"停用"这条规则被绕过，
 * 而代码看起来完全正确。这里用真实 MySQL + 独立连接把这个交错按真实顺序构造出来。</p>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>（与 {@code DepartureAdminContractIntegrationTest}
 * 同一理由）：测试事务会把并发写入并进同一个连接与事务，交错根本无法构造，
 * 而且未提交的夹具对独立连接不可见。因此本类自行造数据，结束后按外键倒序清理。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class HotelAssignmentConcurrencyIntegrationTest {

    @Autowired AdminRouteService adminRouteService;
    @Autowired HotelMapper hotels;
    @Autowired TravelRouteMapper routes;
    @Autowired RouteItineraryDayMapper days;
    @Autowired SysUserMapper users;
    @Autowired OperationLogMapper operationLogs;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    private Long routeId;
    private Long operatorId;
    private Long hotelId;
    /** 用例中额外创建的酒店，按创建顺序清理。 */
    private final List<Long> extraHotelIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SysUser operator = new SysUser();
        operator.username = "hotel_race_" + shortId();
        operator.nickname = "酒店并发回归";
        operator.realName = "酒店并发回归";
        operator.passwordHash = "unused-test-hash";
        operator.status = 1;
        operator.deleted = 0;
        users.insert(operator);
        operatorId = operator.id;

        TravelRoute route = new TravelRoute();
        route.name = "酒店并发回归线路-" + shortId();
        route.departureCity = "昆明";
        route.destination = "大理";
        route.durationDays = 1;
        route.status = "DRAFT";
        route.ratingAvg = BigDecimal.ZERO;
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        routeId = route.id;

        hotelId = activeHotel("并发回归酒店-" + shortId());
    }

    @AfterEach
    void cleanUp() {
        operationLogs.delete(new QueryWrapper<OperationLog>().eq("operator_id", operatorId));
        days.delete(new QueryWrapper<RouteItineraryDay>().eq("route_id", routeId));
        routes.deleteById(routeId);
        for (Long id : extraHotelIds) {
            hotels.deleteById(id);
        }
        hotels.deleteById(hotelId);
        users.deleteById(operatorId);
    }

    /**
     * 核心交错：读状态 → （另一个连接）停用并提交 → 写行程。
     *
     * <p>修复前 {@code requireHotel} 用的是普通查询：第 ① 步已经建立了快照，
     * 第 ③ 步仍会从快照里读到 {@code ACTIVE}，行程照样落库 —— 本用例会红。
     * 修复后走 {@code SELECT ... FOR UPDATE} 当前读，必须读到 {@code DISABLED} 并回 422。</p>
     */
    @Test
    @DisplayName("并发：读状态与写行程之间酒店被停用并提交，必须拒绝安排新行程")
    void assignmentIsRejectedWhenTheHotelIsDisabledBetweenReadAndWrite() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        BusinessException[] captured = new BusinessException[1];

        try {
            tx.execute(status -> {
                // ① 事务内先普通读一次酒店：建立一致性读快照（service 内部的第一步就是这种读）。
                assertEquals(1, hotels.selectById(hotelId).status, "前提：酒店当前是启用状态");

                // ② 另一个连接完成「停用」并提交 —— 后台把 status 改成 DISABLED 的等价动作。
                runOnSeparateConnection("UPDATE hotel SET status = 0 WHERE id = " + hotelId);

                // ③ 同一事务内把这家酒店安排进第 1 天。
                try {
                    adminRouteService.createDay(routeId, dayRequest(1, hotelId), operatorId);
                } catch (BusinessException expected) {
                    captured[0] = expected;
                }
                return null;
            });
        } catch (UnexpectedRollbackException expected) {
            // 内层失败会把共享事务标记为 rollback-only，属于预期。
        }

        assertNotNull(captured[0], "酒店在读写之间被停用，必须拒绝安排新行程");
        assertEquals(422, captured[0].getStatus());
        assertEquals("VALIDATION_ERROR", captured[0].getCode());
        assertEquals(0L, committedDayCount(), "被拒的请求不得留下任何行程");
    }

    /**
     * 串行化：另有一笔停用正在进行（持有该酒店行的排他锁、尚未提交）时，
     * 安排请求必须<b>等待</b>这把锁，而不是读到旧快照直接穿过去。
     */
    @Test
    @DisplayName("并发：停用未提交时安排请求必须等待酒店行锁，提交后读到 DISABLED 并拒绝")
    void assignmentWaitsWhileADisableIsStillInFlight() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                // 停用已执行但未提交：该行被排他锁占用，任何当前读都要排队。
                statement.executeUpdate("UPDATE hotel SET status = 0 WHERE id = " + hotelId);
            }

            Future<BusinessException> pending = executor.submit(() -> {
                try {
                    adminRouteService.createDay(routeId, dayRequest(1, hotelId), operatorId);
                    return null;
                } catch (BusinessException expected) {
                    return expected;
                }
            });

            // 锁还没释放：此刻安排请求既不能成功、也不能失败，只能等待。
            Thread.sleep(500);
            assertFalse(pending.isDone(),
                    "停用尚未提交时安排请求就返回了 —— 状态检查与行程写入没有串行化");

            blocker.commit();

            BusinessException failure = pending.get(20, TimeUnit.SECONDS);
            assertNotNull(failure, "停用提交后，安排请求应读到 DISABLED 并拒绝");
            assertEquals(422, failure.getStatus());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "工作线程应当已经结束");
        }
        assertEquals(0L, committedDayCount(), "被拒的请求不得留下任何行程");
    }

    /** 修改行程的同一道缝：换成一家"在读与写之间被停用"的酒店同样必须被拒。 */
    @Test
    @DisplayName("并发：换挂的目标酒店在读写之间被停用必须拒绝，且当天仍指向原酒店")
    void switchingToAHotelDisabledBetweenReadAndWriteIsRejected() {
        Long originalHotelId = hotelId;
        Long targetHotelId = activeHotel("并发回归酒店二-" + shortId());
        Long dayId = committedDay(1, originalHotelId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        BusinessException[] captured = new BusinessException[1];

        try {
            tx.execute(status -> {
                assertEquals(1, hotels.selectById(targetHotelId).status, "前提：目标酒店当前是启用状态");
                runOnSeparateConnection("UPDATE hotel SET status = 0 WHERE id = " + targetHotelId);

                try {
                    adminRouteService.updateDay(dayId, dayRequest(1, targetHotelId), operatorId);
                } catch (BusinessException expected) {
                    captured[0] = expected;
                }
                return null;
            });
        } catch (UnexpectedRollbackException expected) {
            // 内层失败会把共享事务标记为 rollback-only，属于预期。
        }

        assertNotNull(captured[0], "目标酒店已被停用，必须拒绝换挂");
        assertEquals(422, captured[0].getStatus());
        assertEquals(originalHotelId, days.selectById(dayId).hotelId, "422 时不得改动当天安排的酒店");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 第 {@code dayNumber} 天安排 {@code hotelId} 的行程请求。
     *
     * <p>{@code accommodationType} 显式写成 {@link AccommodationType#HOTEL}：本类实测的是
     * "停用酒店不能再被安排进新行程"这条规则，它只在 {@code HOTEL} 分支上成立。
     * 不写类型时服务端会按 {@code hotelId} 推断出同一个结果，但显式写出来才能让
     * "这条用例在测哪种住宿安排"一目了然 —— 若哪天推断口径变了，隐式写法会让用例悄悄测不到东西。</p>
     */
    private ItineraryDayRequest dayRequest(int dayNumber, Long hotelId) {
        return new ItineraryDayRequest(dayNumber, "第 " + dayNumber + " 天", "抵达并入住", null, null,
                AccommodationType.HOTEL, null, null, null, null, hotelId);
    }

    /** 直接落库一家启用酒店并登记清理（测试类非事务，写入立即提交、对独立连接可见）。 */
    private Long activeHotel(String name) {
        Hotel hotel = new Hotel();
        hotel.name = name;
        // city 是契约 HotelCreateRequest 的必填项（库内为 NOT NULL DEFAULT ''）：
        // 夹具也按"真实建档"的口径填写，避免造出一批不存在于真实数据里的无城市酒店。
        hotel.city = "大理";
        hotel.address = "云南省昆明市并发回归路 1 号";
        hotel.dataSource = "团队测试数据";
        hotel.status = 1;
        hotels.insert(hotel);
        extraHotelIds.add(hotel.id);
        return hotel.id;
    }

    /** 直接落库一天行程并提交，返回行程 id。 */
    private Long committedDay(int dayNumber, Long hotelId) {
        RouteItineraryDay day = new RouteItineraryDay();
        day.routeId = routeId;
        day.dayNumber = dayNumber;
        day.title = "第 " + dayNumber + " 天";
        day.hotelId = hotelId;
        // 列有默认值，但数据库不允许"关联了酒店、类型却是待确认"这种自相矛盾的行
        // （约束 ck_day_accommodation），所以直接落库的夹具必须显式写明类型。
        day.accommodationType = hotelId == null ? AccommodationType.PENDING : AccommodationType.HOTEL;
        days.insert(day);
        return day.id;
    }

    /** 用独立连接读已提交的行程条数，避免读到本测试线程的快照。 */
    private long committedDayCount() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT COUNT(*) FROM route_itinerary_day WHERE route_id = " + routeId)) {
            assertTrue(result.next(), "COUNT 查询应当有结果行");
            return result.getLong(1);
        } catch (Exception cause) {
            throw new IllegalStateException("读取行程条数失败", cause);
        }
    }

    /**
     * 在<b>独立连接</b>上执行写入并提交，模拟另一位工作人员的并发操作。
     *
     * <p>不能用 {@code DataSourceUtils.getConnection}：它会返回当前事务绑定的连接，
     * 那样这次写入就落回同一个事务，既不会提交也制造不出"读写之间被改掉"的交错。</p>
     */
    private void runOnSeparateConnection(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (Exception cause) {
            throw new IllegalStateException("并发写入失败：" + sql, cause);
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
