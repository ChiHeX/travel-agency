package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.service.DepartureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 团期状态机迁移的并发回归测试（PR #19 评审要求）。
 *
 * <p>顺序重复调用（第二次 start/complete 应 409）已由 {@link GuideTripContractIntegrationTest} 覆盖，
 * 但它拦不住真正的并发：原先"先 select 判状态、再 updateById 写状态"的实现里，
 * 两个请求可以同时读到 OPEN / TRAVELLING，随后双双更新成功——完成团期时还会重复执行订单级联
 * 并覆盖 {@code completed_at}。本类用多个真实线程同时发起迁移，断言
 * <b>恰好一个请求成功、其余全部 409 {@code DEPARTURE_STATE_CONFLICT}</b>，
 * 即前置状态确实作为 UPDATE 条件参与竞争（原子闸门），而不是只靠应用层判断。</p>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：测试事务会把这些请求并入同一个连接与事务，
 * 并发就退化成顺序执行、测不出任何东西。因此本类自行插入并<b>在结束后逐条删除</b>造的
 * 线路 / 导游 / 团期 / 订单，不留脏数据。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class DepartureStateConcurrencyIntegrationTest {

    private static final int THREADS = 8;

    @Autowired DepartureService departureService;
    @Autowired DepartureMapper departures;
    @Autowired TravelRouteMapper routes;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;
    @Autowired TravelOrderMapper orders;

    private Long routeId;
    private Long guideId;
    private Long userId;
    private Long departureId;

    @BeforeEach
    void setUp() {
        TravelRoute route = new TravelRoute();
        route.name = "并发回归线路";
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

        SysUser user = new SysUser();
        user.username = "dep_race_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.nickname = "并发回归导游";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        userId = user.id;

        Guide guide = new Guide();
        guide.userId = user.id;
        guide.name = "并发回归导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        guideId = guide.id;

        departureId = departure("OPEN");
    }

    @AfterEach
    void tearDown() {
        // 按外键依赖倒序清理：订单 → 团期 → 线路 → 导游 → 用户。
        if (departureId != null) {
            orders.delete(new QueryWrapper<TravelOrder>().eq("departure_id", departureId));
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
    }

    /** 并发 start：只有抢到状态迁移的请求返回成功，其余必须 409，且团期状态只被改写一次。 */
    @Test
    void concurrentStartAllowsExactlyOneWinner() throws Exception {
        RaceResult result = race(THREADS, () -> departureService.start(departureId));

        assertOnlyOneWinner(result);
        assertEquals("TRAVELLING", departures.selectById(departureId).status,
                "并发 start 之后团期应当只前进一步");
    }

    /**
     * 并发 complete：同样只有一个请求赢，订单级联只可能由赢家执行（输家根本走不到级联），
     * 且 {@code completed_at} 只被写入一次——事后再调一次 complete 仍是 409、时间戳不变。
     */
    @Test
    void concurrentCompleteCascadesOrdersOnlyOnce() throws Exception {
        departures.update(null, new UpdateWrapper<Departure>()
                .eq("id", departureId).set("status", "TRAVELLING"));
        TravelOrder order = order("CONFIRMED");

        RaceResult result = race(THREADS, () -> departureService.complete(departureId));

        assertOnlyOneWinner(result);
        assertEquals("FINISHED", departures.selectById(departureId).status, "并发 complete 之后团期应当只前进一步");

        TravelOrder finished = orders.selectById(order.id);
        assertEquals("COMPLETED", finished.status, "行程结束后已确认订单应完成");
        assertNotNull(finished.completedAt, "完成时间应写入");

        LocalDateTime cascadedAt = finished.completedAt;
        BusinessException repeated = assertThrowsBusinessConflict(() -> departureService.complete(departureId));
        assertEquals(409, repeated.getStatus());
        assertEquals(cascadedAt, orders.selectById(order.id).completedAt,
                "重复 complete 不得再次级联、更不得覆盖 completed_at");
    }

    // ------------------------------------------------------------------
    // 并发脚手架与断言
    // ------------------------------------------------------------------

    /** 用 {@link CountDownLatch} 把 N 个线程同时放行，收集"成功"与被闸门拦下的业务异常。 */
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
                    return null;                 // 迁移成功
                } catch (BusinessException failure) {
                    return failure;              // 被原子闸门拦下
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未能全部就绪");
        go.countDown();

        List<BusinessException> conflicts = new ArrayList<>();
        int succeeded = 0;
        try {
            for (Future<BusinessException> future : futures) {
                BusinessException failure = future.get(30, TimeUnit.SECONDS);
                if (failure == null) {
                    succeeded++;
                } else {
                    conflicts.add(failure);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new RaceResult(succeeded, conflicts);
    }

    private void assertOnlyOneWinner(RaceResult result) {
        assertEquals(1, result.succeeded(), "并发的状态迁移只允许一个请求成功");
        assertEquals(THREADS - 1, result.conflicts().size(), "其余请求都应在原子闸门处被拒绝");
        for (BusinessException conflict : result.conflicts()) {
            assertEquals(409, conflict.getStatus(), "冲突必须是 409");
            assertEquals("DEPARTURE_STATE_CONFLICT", conflict.getCode(), "冲突必须回契约错误码");
        }
    }

    /** 顺序调用一次，断言抛出 409 业务异常并返回该异常（用于重复调用场景）。 */
    private BusinessException assertThrowsBusinessConflict(Runnable action) {
        try {
            action.run();
        } catch (BusinessException failure) {
            return failure;
        }
        throw new AssertionError("预期抛出 DEPARTURE_STATE_CONFLICT，但调用成功了");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record RaceResult(int succeeded, List<BusinessException> conflicts) {
    }

    // ------------------------------------------------------------------
    // 测试数据
    // ------------------------------------------------------------------

    private Long departure(String status) {
        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(20);
        departure.endDate = LocalDate.now().plusDays(22);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = 20;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 0;
        departure.guideId = guideId;
        departure.status = status;
        departure.version = 0;
        departures.insert(departure);
        return departure.id;
    }

    private TravelOrder order(String status) {
        TravelOrder order = new TravelOrder();
        order.orderNo = "RACE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        order.userId = userId;
        order.routeId = routeId;
        order.departureId = departureId;
        order.contactName = "并发回归联系人";
        order.contactPhone = "13800000001";
        order.adultCount = 1;
        order.childCount = 0;
        order.adultUnitPrice = new BigDecimal("2999.00");
        order.childUnitPrice = new BigDecimal("1999.00");
        order.totalAmount = new BigDecimal("2999.00");
        order.status = status;
        order.paymentStatus = "PAID";
        orders.insert(order);
        return order;
    }
}
