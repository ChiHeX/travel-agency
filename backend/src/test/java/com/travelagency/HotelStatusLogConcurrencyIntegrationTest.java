package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.domain.dto.HotelUpdateRequest;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.OperationLogMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.service.HotelService;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酒店状态审计日志（{@code HotelService#update} 的 {@code STATUS} 记录）的并发交错实测。
 *
 * <p><b>为什么需要这个类</b>：{@code STATUS} 日志的规则是"状态确实发生变化时才记"，
 * 而"是否变化"取决于比较基准 —— 修复前这个基准来自<b>普通查询</b>读出的状态，
 * 读的是本事务的一致性快照。两人同时显式提交状态时，快照里的状态可能早就过期：</p>
 * <ul>
 *   <li><b>多记</b>：对方刚把酒店停用并提交（或正持有未提交的停用），本事务仍从旧快照读到
 *       {@code ACTIVE}，于是把一次"没有改变任何东西"的提交记成 ACTIVE → DISABLED；</li>
 *   <li><b>漏记</b>：本事务开始后对方完成停用，本事务再把状态改回 {@code ACTIVE}，
 *       真实的 DISABLED → ACTIVE 因为比较基准是旧快照而完全不落日志。</li>
 * </ul>
 * <p>两种情况都发生在"读状态"与"写状态"之间（也正是操作日志要追溯的那个窗口）。
 * 修复后读取走行锁下的当前读，判定、写入与日志同处一把锁内。</p>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：测试事务会把并发写入并进同一个连接与事务，
 * 交错构造不出来，未提交的夹具对独立连接也不可见。本类自行造数据并清理干净。
 * 需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class HotelStatusLogConcurrencyIntegrationTest {

    @Autowired HotelService hotelService;
    @Autowired HotelMapper hotels;
    @Autowired OperationLogMapper operationLogs;
    @Autowired SysUserMapper users;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    private Long hotelId;
    private Long operatorId;

    @BeforeEach
    void setUp() {
        SysUser operator = new SysUser();
        operator.username = "hotel_log_" + shortId();
        operator.nickname = "酒店状态日志并发回归";
        operator.realName = "酒店状态日志并发回归";
        operator.passwordHash = "unused-test-hash";
        operator.status = 1;
        operator.deleted = 0;
        users.insert(operator);
        operatorId = operator.id;

        Hotel hotel = new Hotel();
        hotel.name = "状态日志回归酒店-" + shortId();
        hotel.city = "大理";
        hotel.dataSource = "团队测试数据";
        hotel.status = 1;
        hotels.insert(hotel);
        hotelId = hotel.id;
    }

    @AfterEach
    void cleanUp() {
        operationLogs.delete(new QueryWrapper<OperationLog>().eq("operator_id", operatorId));
        hotels.deleteById(hotelId);
        users.deleteById(operatorId);
    }

    /**
     * 漏记：比较基准不能是事务开始时的旧快照。
     *
     * <p>① 事务内先普通读一次建立快照 → ② 另一个连接停用并提交 → ③ 同一事务内显式启用。
     * 真实变化是 DISABLED → ACTIVE，必须留痕；修复前会读到快照里的 ACTIVE，
     * 与请求值相同而不记日志，这次真实的启用从此无从追溯。</p>
     */
    @Test
    @DisplayName("并发：停用先提交，同一事务内再启用必须记下 DISABLED → ACTIVE 这次真实变化")
    void realTransitionIsLoggedEvenWhenTheSnapshotIsStale() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.execute(status -> {
            // ① 普通读一次，建立一致性读快照（修复前 service 的比较基准就是它）。
            assertEquals(1, hotels.selectById(hotelId).status, "前提：酒店当前是启用状态");

            // ② 另一位管理员把酒店停用并提交。
            runOnSeparateConnection("UPDATE hotel SET status = 0 WHERE id = " + hotelId);

            // ③ 同一事务内显式启用：真实变化 DISABLED → ACTIVE。
            hotelService.update(hotelId, upsert("ACTIVE"), operatorId);
            return null;
        });

        assertEquals(List.of("酒店状态由 DISABLED 变更为 ACTIVE"), statusLogs(),
                "真实发生的状态变化必须记入审计日志");
        assertEquals(1, updateLogs().size(), "资料修改本身仍要留痕");
        assertEquals(1, hotels.selectById(hotelId).status, "启用必须真的落库");
    }

    /**
     * 多记：别人刚完成的停用不该被记成本次请求造成的状态变化。
     *
     * <p>停用已在另一事务中执行（尚未提交），本请求随后提交同一个 {@code DISABLED}：
     * 结果是这家酒店本来就已经是停用，本次提交没有改变任何东西。修复前会从旧快照读到
     * {@code ACTIVE}，把这次提交记成 ACTIVE → DISABLED。</p>
     */
    @Test
    @DisplayName("并发：另一笔停用未提交时，本请求提交同一个停用不得记出一次并未发生的变化")
    void noSpuriousTransitionLogWhenSomeoneElseAlreadyDisabledTheHotel() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                // 停用已执行但未提交：该行被排他锁占用。
                statement.executeUpdate("UPDATE hotel SET status = 0 WHERE id = " + hotelId);
            }

            Future<?> pending = executor.submit(
                    () -> hotelService.update(hotelId, upsert("DISABLED"), operatorId));

            Thread.sleep(500);
            assertFalse(pending.isDone(), "另一笔停用未提交时，本请求必须等待酒店行锁");

            blocker.commit();
            pending.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "工作线程应当已经结束");
        }

        assertEquals(List.of(), statusLogs(),
                "酒店本来就是停用状态，这次提交没有造成状态变化，不得记出一次不存在的变化");
        assertEquals(1, updateLogs().size(), "资料修改本身仍要留痕");
        assertEquals(0, hotels.selectById(hotelId).status, "库内仍应是停用");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private HotelUpdateRequest upsert(String status) {
        return new HotelUpdateRequest("状态日志回归酒店", "大理", null, null, null,
                null, null, null, null, null, null, null, null,
                "团队测试数据", status, 0);
    }

    /** 本次操作人对该酒店写的 {@code STATUS} 日志明细。 */
    private List<String> statusLogs() {
        return operationLogs.selectList(logQuery("STATUS")).stream().map(log -> log.detail).toList();
    }

    private List<OperationLog> updateLogs() {
        return operationLogs.selectList(logQuery("UPDATE"));
    }

    private QueryWrapper<OperationLog> logQuery(String operationType) {
        return new QueryWrapper<OperationLog>()
                .eq("operator_id", operatorId)
                .eq("object_type", "HOTEL")
                .eq("object_id", String.valueOf(hotelId))
                .eq("operation_type", operationType);
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
