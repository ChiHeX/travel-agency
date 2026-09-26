package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.dto.DepartureCreateRequest;
import com.travelagency.domain.dto.DepartureUpdateRequest;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 团期管理（契约 Admin Departures）的数据库集成测试：使用真实 HTTP 处理链、JWT、
 * MyBatis-Plus 与 MySQL，覆盖只有连库才能验证的部分。
 *
 * <p>需要数据库：</p>
 * <pre>
 * $env:TRAVEL_MYSQL_TEST = "true"
 * mvn -ntp test -Dtest=DepartureAdminContractIntegrationTest
 * </pre>
 *
 * <p><b>刻意不使用类级 {@code @Transactional}：</b>最关键的那个用例需要在事务持有快照期间，
 * 从另一个连接提交一次并发占位，测试自身必须先有已提交的数据。因此按
 * {@code DepartureStateConcurrencyIntegrationTest} 的做法手工建数据、手工清理。</p>
 *
 * <p>覆盖范围：创建 201 + Location + 契约字段、契约外字段 400、修改不覆盖服务端字段、
 * 名额低于已占用 422、改挂线路的草稿限制、状态端点返回更新后的团期，
 * 以及<b>「读取后并发占位、条件更新匹配 0 行」必须失败而不是报成功</b>。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class DepartureAdminContractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired DepartureService departureService;
    @Autowired DepartureMapper departures;
    @Autowired TravelRouteMapper routes;
    @Autowired TravelOrderMapper orders;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;
    @Autowired OperationLogMapper operationLogs;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    private MockMvc mvc;
    private String staffToken;
    private Long staffUserId;

    private Long routeId;
    private Long otherRouteId;
    private Long guideId;
    private Long guideUserId;
    private Long staffAccountId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        routeId = insertRoute("团期契约线路-" + suffix());
        otherRouteId = insertRoute("团期改挂目标线路-" + suffix());

        guideUserId = insertUser("dep_guide_" + suffix(), "团期契约导游");
        Guide guide = new Guide();
        guide.userId = guideUserId;
        guide.name = "团期契约导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        guideId = guide.id;

        staffAccountId = insertUser("dep_staff_" + suffix(), "团期契约员工");
        staffUserId = staffAccountId;
        staffToken = "Bearer " + tokens.createToken(staffAccountId, "dep_staff", Set.of("STAFF"));
    }

    @AfterEach
    void tearDown() {
        // 按外键依赖倒序清理：操作日志 → 订单 → 团期 → 线路 → 导游 → 用户。
        // operation_log.operator_id 有指向 sys_user 的外键，而团期写操作会记录操作日志，
        // 不先删日志就删不掉操作人账号。
        if (staffAccountId != null) {
            operationLogs.delete(new QueryWrapper<OperationLog>().eq("operator_id", staffAccountId));
        }
        orders.delete(new QueryWrapper<TravelOrder>().in("route_id", List.of(routeId, otherRouteId)));
        departures.delete(new QueryWrapper<Departure>().in("route_id", List.of(routeId, otherRouteId)));
        routes.deleteById(routeId);
        routes.deleteById(otherRouteId);
        if (guideId != null) {
            guides.deleteById(guideId);
        }
        if (guideUserId != null) {
            users.deleteById(guideUserId);
        }
        if (staffAccountId != null) {
            users.deleteById(staffAccountId);
        }
    }

    // ------------------------------------------------------------------
    // HTTP 契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建：201 + Location + 契约 Departure 字段，状态固定 DRAFT，导游可分配")
    void createsDraftDepartureWith201AndLocation() throws Exception {
        var response = mvc.perform(post("/api/admin/departures").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureBody(routeId, 30, guideId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.reservedPeople").value(0))
                .andExpect(jsonPath("$.data.confirmedPeople").value(0))
                .andExpect(jsonPath("$.data.availableSeats").value(30))
                .andExpect(jsonPath("$.data.routeName").isNotEmpty())
                .andExpect(jsonPath("$.data.guideName").value("团期契约导游"))
                .andReturn().getResponse();

        JsonNode data = json.readTree(response.getContentAsString()).get("data");
        assertEquals("/api/admin/departures/" + data.get("id").asString(), response.getHeader("Location"));
        for (String field : List.of("id", "routeId", "startDate", "endDate", "adultPrice", "childPrice",
                "maxPeople", "reservedPeople", "confirmedPeople", "availableSeats", "status", "version",
                "createdAt", "updatedAt")) {
            assertTrue(data.has(field), "响应缺少契约字段：" + field);
        }
        // 乐观锁版本号由服务端从 0 起算，客户端不参与决定。
        assertEquals(0, data.get("version").asInt(), "新建团期的版本号应当从 0 开始");
    }

    @Test
    @DisplayName("创建：status / reservedPeople 等契约外字段被严格模式拒绝（400）")
    void rejectsServerOwnedFieldsOnCreate() throws Exception {
        mvc.perform(post("/api/admin/departures").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureBody(routeId, 30, null)
                                .replace("\"maxPeople\":30", "\"maxPeople\":30,\"status\":\"OPEN\"")))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/admin/departures").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureBody(routeId, 30, null)
                                .replace("\"maxPeople\":30", "\"maxPeople\":30,\"reservedPeople\":99")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("修改：不覆盖名额计数与状态，只写契约允许的可编辑字段")
    void updateKeepsSeatCountersAndStatus() throws Exception {
        Long id = createDeparture(routeId, 30, "OPEN");
        // 模拟已有订单占位：预留 4、确认 8。
        setSeats(id, 4, 8);

        mvc.perform(put("/api/admin/departures/" + id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(routeId, 40, null, currentVersion(id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxPeople").value(40))
                .andExpect(jsonPath("$.data.reservedPeople").value(4))
                .andExpect(jsonPath("$.data.confirmedPeople").value(8))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.availableSeats").value(28))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("修改：最大人数小于已占用名额返回 422，且不改动任何字段")
    void updateRejectsCapacityBelowOccupiedSeats() throws Exception {
        Long id = createDeparture(routeId, 30, "OPEN");
        setSeats(id, 4, 8);

        mvc.perform(put("/api/admin/departures/" + id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(routeId, 11, null, currentVersion(id))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertEquals(30, departures.selectById(id).maxPeople.intValue(), "校验失败不能改写任何字段");
        assertEquals(0, currentVersion(id), "校验失败不能推进版本号");
    }

    @Test
    @DisplayName("修改：只有草稿团期可以改挂线路，已开放报名返回 409")
    void rebindRequiresDraftStatus() throws Exception {
        Long id = createDeparture(routeId, 30, "DRAFT");

        mvc.perform(put("/api/admin/departures/" + id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(otherRouteId, 30, null, currentVersion(id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routeId").value(String.valueOf(otherRouteId)))
                .andExpect(jsonPath("$.data.version").value(1));

        // 上架之后即使没有订单也不允许改挂。
        mvc.perform(patch("/api/admin/departures/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        // 提交当前版本，让冲突原因落在「改挂闸门」而不是「版本过期」上。
        mvc.perform(put("/api/admin/departures/" + id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(routeId, 30, null, currentVersion(id))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTURE_STATE_CONFLICT"));

        assertEquals(otherRouteId, departures.selectById(id).routeId, "冲突时不能改动线路");
    }

    // ------------------------------------------------------------------
    // 乐观锁：基于过期数据的提交
    // ------------------------------------------------------------------

    /**
     * 两位工作人员各自打开同一条团期、先后保存：后保存的人不能悄悄覆盖前一位的改动。
     *
     * <p>构造方式与真实场景一致：A 读到版本 v，B 先改成功（版本变成 v+1），
     * A 再拿着 v 提交 —— 必须 409 {@code DEPARTURE_VERSION_CONFLICT}，
     * 且 B 的改动不能被覆盖。</p>
     */
    @Test
    @DisplayName("乐观锁：基于过期版本的修改被拒（409 DEPARTURE_VERSION_CONFLICT），且不覆盖他人改动")
    void updateRejectsStaleVersion() throws Exception {
        Long id = createDeparture(routeId, 30, "DRAFT");
        int versionReadByFirstEditor = currentVersion(id);

        // 第二位工作人员先保存成功：把上限改成 40，版本推进到 1。
        mvc.perform(put("/api/admin/departures/" + id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(routeId, 40, null, versionReadByFirstEditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        // 第一位工作人员拿着旧版本提交，想改成 25：必须被拒。
        mvc.perform(put("/api/admin/departures/" + id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(routeId, 25, null, versionReadByFirstEditor)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTURE_VERSION_CONFLICT"));

        Departure after = departures.selectById(id);
        assertEquals(40, after.maxPeople.intValue(), "他人的改动不得被过期提交覆盖");
        assertEquals(1, after.version.intValue(), "失败提交不能推进版本号");
    }

    /** 版本号缺失或为负属于请求字段问题（422），不能落成"静默按 0 处理"。 */
    @Test
    @DisplayName("乐观锁：修改请求缺少 version 返回 422")
    void updateRequiresVersion() throws Exception {
        Long id = createDeparture(routeId, 30, "DRAFT");

        mvc.perform(put("/api/admin/departures/" + id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureBody(routeId, 30, null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    /** 创建请求不接受 version：它由服务端从 0 起算，客户端提交会被严格模式拒绝。 */
    @Test
    @DisplayName("乐观锁：创建请求不接受 version（400）")
    void createRejectsVersionField() throws Exception {
        mvc.perform(post("/api/admin/departures").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(routeId, 30, null, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("状态端点：返回更新后的团期视图（此前返回 data:null）")
    void statusEndpointReturnsUpdatedDeparture() throws Exception {
        Long id = createDeparture(routeId, 30, "DRAFT");

        mvc.perform(patch("/api/admin/departures/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(String.valueOf(id)))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.availableSeats").value(30));

        assertEquals("OPEN", departures.selectById(id).status);
    }

    // ------------------------------------------------------------------
    // 并发：读取后占位，条件更新匹配 0 行
    // ------------------------------------------------------------------

    /**
     * 评审要求的场景：<b>事务内先读到「名额充足」，随后另一个连接并发占位并提交，
     * 条件 UPDATE 因此匹配 0 行。</b>此时必须返回 409（fail-closed），
     * 绝不能凭一次普通回读把没写进去的修改报成成功。
     *
     * <p>本用例只有连真库才有意义：REPEATABLE READ 下，同一事务里普通的 {@code SELECT}
     * 会复用第一次读建立的快照，看不到并发提交，于是「条件未生效」会伪装成
     * 「目标状态已达成」。用 Mockito 把第二次读 stub 成新数据模拟不出这个行为。</p>
     */
    @Test
    @DisplayName("并发：读取后被占位导致条件更新为 0 行时必须返回 409，且不改动任何字段")
    void updateFailsWhenSeatsAreTakenAfterTheRead() throws Exception {
        Long id = createDeparture(routeId, 30, "OPEN");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        BusinessException[] captured = new BusinessException[1];

        try {
            tx.execute(status -> {
                // ① 事务内第一次读：这一步建立一致性读快照（service.update 内部的第一步同理）。
                Departure snapshot = departures.selectById(id);
                assertEquals(0, snapshot.reservedPeople.intValue(), "并发占位之前快照里应当是 0 人");

                // ② 另一个连接占位 20 人并立即提交（独立连接，不参与本事务）。
                occupySeatsOnSeparateConnection(id, 20);

                // ③ 前置校验用的是本次「已过时」的快照：10 < 0 不成立，因此会放行到条件 UPDATE。
                try {
                    departureService.update(id, updateRequest(routeId, 10, currentVersion(id)), staffUserId);
                    // 走到这里说明把没生效的修改报成了成功 —— 正是要防的回归。
                } catch (BusinessException expected) {
                    captured[0] = expected;
                }
                return null;
            });
        } catch (UnexpectedRollbackException expected) {
            // 内层 @Transactional 抛错会把共享事务标记为 rollback-only，属于预期。
        }

        assertNotNull(captured[0], "条件更新未生效时必须抛业务异常，不能返回成功");
        assertEquals(409, captured[0].getStatus());
        assertEquals("DEPARTURE_CAPACITY_CONFLICT", captured[0].getCode());

        // 并发占位的 20 人确实已经提交（用独立连接读已提交结果）。
        assertEquals(20, committedReservedPeople(id), "并发占位的 20 人应当已经落库");
        // 失败的更新不得留下任何痕迹。
        Departure after = departures.selectById(id);
        assertEquals(30, after.maxPeople.intValue(), "失败的更新不得改动 max_people");
        assertEquals(20, after.reservedPeople.intValue(), "失败的更新不得改动 reserved_people");
    }

    // ------------------------------------------------------------------
    // 状态守卫
    // ------------------------------------------------------------------

    /**
     * 原样重复保存必须成功，并且版本前进一步。
     *
     * <p>加入乐观锁之后这条语句的 {@code SET} 里始终有 {@code version = version + 1}，
     * 所以只要 WHERE 命中就一定改变了字段 —— "影响 0 行"不再有"字段没变化"这种歧义，
     * 也就不再依赖驱动的 {@code useAffectedRows} 语义去猜。本用例在两种驱动配置下都应通过。</p>
     */
    @Test
    @DisplayName("修改：原样重复保存成功且版本推进（两种 useAffectedRows 语义下都成立）")
    void updateAcceptsUnchangedPayload() throws Exception {
        Long id = createDeparture(routeId, 30, "OPEN");

        mvc.perform(put("/api/admin/departures/" + id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(routeId, 30, null, currentVersion(id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxPeople").value(30))
                .andExpect(jsonPath("$.data.version").value(1));

        assertEquals(30, departures.selectById(id).maxPeople.intValue());
    }

    @Test
    @DisplayName("状态：终态不能回退（已完成 / 已取消）")
    void terminalStatusCannotBeReverted() throws Exception {
        Long id = createDeparture(routeId, 30, "OPEN");

        mvc.perform(patch("/api/admin/departures/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FINISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINISHED"));

        mvc.perform(patch("/api/admin/departures/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTURE_STATE_CONFLICT"));

        assertEquals("FINISHED", departures.selectById(id).status, "终态不得被回退");
    }

    @Test
    @DisplayName("状态：已经出发的团期不能开放报名")
    void pastDatedDepartureCannotBeOpened() throws Exception {
        LocalDate start = LocalDate.now().minusDays(3);
        String body = "{\"routeId\":\"" + routeId + "\",\"startDate\":\"" + start + "\",\"endDate\":\""
                + start.plusDays(2) + "\",\"adultPrice\":\"2999.00\",\"childPrice\":\"1999.00\","
                + "\"maxPeople\":30}";
        String response = mvc.perform(post("/api/admin/departures").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long id = Long.valueOf(json.readTree(response).get("data").get("id").asString());

        mvc.perform(patch("/api/admin/departures/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTURE_STATE_CONFLICT"));

        assertEquals("DRAFT", departures.selectById(id).status, "已出发的团期不得被打开报名");
    }

    // ------------------------------------------------------------------
    // 并发：同一导游的时间冲突
    // ------------------------------------------------------------------

    /**
     * 同一导游、同一时间段并发创建：只允许一个成功。
     *
     * <p>"同一导游同一时间范围不能带两个团"是范围重叠判断，无法用唯一键表达，只能先查再写。
     * 若不锁导游行、或用普通查询做判定，并发请求会各自查到"没有冲突"再各自插入。
     * 这里用真实并发验证：先锁导游行串行化，再用 {@code FOR UPDATE} 当前读判定重叠。</p>
     */
    @Test
    @DisplayName("并发：同一导游同一时间段只能创建出一个团期")
    void concurrentCreateForSameGuideKeepsExactlyOneDeparture() throws Exception {
        int threads = 4;
        LocalDate start = LocalDate.now().plusDays(40);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BusinessException>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    awaitLatch(go);
                    try {
                        departureService.create(new DepartureCreateRequest(routeId, start, start.plusDays(3),
                                new BigDecimal("2999.00"), new BigDecimal("1999.00"), 20, guideId), staffUserId);
                        return null;
                    } catch (BusinessException failure) {
                        return failure;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未能全部就绪");
            go.countDown();

            int succeeded = 0;
            List<BusinessException> conflicts = new ArrayList<>();
            for (Future<BusinessException> future : futures) {
                BusinessException failure = future.get(60, TimeUnit.SECONDS);
                if (failure == null) {
                    succeeded++;
                } else {
                    conflicts.add(failure);
                }
            }
            assertEquals(1, succeeded, "同一导游的重叠团期只允许一个创建成功");
            assertEquals(threads - 1, conflicts.size(), "其余请求都应在导游冲突处被拒绝");
            for (BusinessException conflict : conflicts) {
                assertEquals(409, conflict.getStatus());
                assertEquals("DEPARTURE_STATE_CONFLICT", conflict.getCode());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1L, departures.selectCount(new QueryWrapper<Departure>()
                        .eq("guide_id", guideId).eq("start_date", start)).longValue(),
                "库里也只能留下一行");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(cause);
        }
    }

    /**
     * 评审指出的改挂竞态：改挂期间团期被「上架 → 下单 → 退回草稿」。
     *
     * <p>只给写入条件加 {@code status = DRAFT} 拦不住它 —— 三步提交之后状态确实又是 DRAFT。
     * 而"先查有没有订单"如果用的是普通查询，读的是本事务的一致性快照，同样看不到这三步：
     * 快照里既没有订单、状态也仍是 DRAFT，于是改挂照样成功，
     * 订单记录的线路与团期当前线路从此不一致。</p>
     *
     * <p>本用例按真实交错顺序构造：事务内先读一次建立快照 → 另一个连接完整走完
     * 上架 / 下单 / 退回草稿并提交 → 再在同一个事务里改挂。
     * 修复后 {@code update} 会先锁团期行、再用 {@code FOR UPDATE} 当前读订单，
     * 因此必须返回 409，且线路保持不变。</p>
     */
    @Test
    @DisplayName("并发：改挂期间「上架 → 下单 → 退回草稿」的团期必须拒绝改挂")
    void rebindFailsWhenOrderLandsBetweenReadAndWrite() throws Exception {
        Long id = createDeparture(routeId, 30, "DRAFT");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        BusinessException[] captured = new BusinessException[1];

        try {
            tx.execute(status -> {
                // ① 事务内先读一次，建立一致性读快照（service.update 内部的第一步同理）。
                assertEquals("DRAFT", departures.selectById(id).status);

                // ② 另一个连接走完「上架 → 下单（并占名额）→ 退回草稿」，三步全部提交。
                runOnSeparateConnection(
                        "UPDATE departure SET status = 'OPEN', reserved_people = 1 WHERE id = " + id,
                        "INSERT INTO travel_order (order_no, user_id, route_id, departure_id, contact_name,"
                                + " contact_phone, adult_count, child_count, adult_unit_price,"
                                + " child_unit_price, total_amount, status)"
                                + " VALUES ('RACE-" + suffix() + "', " + staffUserId + ", " + routeId + ", "
                                + id + ", '并发回归', '13800000000', 1, 0, 2999.00, 1999.00, 2999.00,"
                                + " 'PAID_WAIT_CONFIRM')",
                        "UPDATE departure SET status = 'DRAFT' WHERE id = " + id);

                // ③ 同一事务内改挂线路：此刻状态看起来仍是 DRAFT，但团期已经产生过订单。
                try {
                    departureService.update(id, updateRequest(otherRouteId, 30, currentVersion(id)), staffUserId);
                } catch (BusinessException expected) {
                    captured[0] = expected;
                }
                return null;
            });
        } catch (UnexpectedRollbackException expected) {
            // 内层失败把共享事务标记为 rollback-only，属于预期。
        }

        assertNotNull(captured[0], "团期在改挂期间产生过订单，必须拒绝改挂");
        assertEquals(409, captured[0].getStatus());
        assertEquals("DEPARTURE_STATE_CONFLICT", captured[0].getCode());

        // 核心不变量：订单记录的线路必须与团期当前线路一致。
        assertOrderRouteMatchesDepartureRoute(id, routeId);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 在独立连接上顺序执行若干条写入并提交。 */
    private void runOnSeparateConnection(String... statements) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.executeUpdate(sql);
            }
        } catch (Exception cause) {
            throw new IllegalStateException("并发写入失败", cause);
        }
    }

    /**
     * 用独立连接读已提交状态，断言订单记录的线路与团期当前线路一致。
     *
     * <p>这正是改挂竞态会破坏的不变量：{@code travel_order} 同时保存 {@code route_id}
     * 与 {@code departure_id}，改挂后两者会指向不同线路。</p>
     */
    private void assertOrderRouteMatchesDepartureRoute(Long departureId, Long expectedRouteId) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT d.route_id AS departure_route, o.route_id AS order_route "
                             + "FROM departure d JOIN travel_order o ON o.departure_id = d.id "
                             + "WHERE d.id = " + departureId)) {
            assertTrue(result.next(), "该团期应当存在订单");
            assertEquals(expectedRouteId.longValue(), result.getLong("departure_route"),
                    "团期应当仍在原线路");
            assertEquals(result.getLong("departure_route"), result.getLong("order_route"),
                    "订单记录的线路必须与团期当前线路一致");
        } catch (Exception cause) {
            throw new IllegalStateException("读取线路一致性失败", cause);
        }
    }

    /**
     * 在<b>独立连接</b>上占位并提交，模拟并发下单。
     *
     * <p>不能用 {@code DataSourceUtils.getConnection}：它会返回当前事务绑定的连接，
     * 那样这次写入就落回同一个事务，既不会提交也看不到快照差异，用例会失去意义。</p>
     */
    private void occupySeatsOnSeparateConnection(Long departureId, int seats) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE departure SET reserved_people = " + seats + " WHERE id = " + departureId);
        } catch (Exception cause) {
            throw new IllegalStateException("并发占位失败", cause);
        }
    }

    /** 用独立连接读一次已提交的预留人数，避免读到本测试线程的快照。 */
    private int committedReservedPeople(Long departureId) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT reserved_people FROM departure WHERE id = " + departureId)) {
            assertTrue(result.next(), "团期应当存在");
            return result.getInt(1);
        } catch (Exception cause) {
            throw new IllegalStateException("读取预留人数失败", cause);
        }
    }

    private void setSeats(Long departureId, int reserved, int confirmed) {
        departures.update(null, new UpdateWrapper<Departure>().eq("id", departureId)
                .set("reserved_people", reserved).set("confirmed_people", confirmed));
    }

    /** 修改请求（契约 DepartureUpdateRequest：比创建多一个必填 version）。 */
    private DepartureUpdateRequest updateRequest(Long routeId, int maxPeople, int version) {
        LocalDate start = LocalDate.now().plusDays(20);
        return new DepartureUpdateRequest(routeId, start, start.plusDays(5),
                new BigDecimal("2999.00"), new BigDecimal("1999.00"), maxPeople, null, version);
    }

    /** 创建请求体（契约 DepartureCreateRequest：不含 version）。 */
    private String departureBody(Long routeId, int maxPeople, Long guideId) {
        return departureBody(routeId, maxPeople, guideId, null);
    }

    /** 带上 version 的请求体，用于 PUT /admin/departures/{departureId}。 */
    private String updateBody(Long routeId, int maxPeople, Long guideId, int version) {
        return departureBody(routeId, maxPeople, guideId, version);
    }

    private String departureBody(Long routeId, int maxPeople, Long guideId, Integer version) {
        LocalDate start = LocalDate.now().plusDays(20);
        return "{\"routeId\":\"" + routeId + "\",\"startDate\":\"" + start + "\",\"endDate\":\""
                + start.plusDays(5) + "\",\"adultPrice\":\"2999.00\",\"childPrice\":\"1999.00\","
                + "\"maxPeople\":" + maxPeople
                + (guideId == null ? "" : ",\"guideId\":\"" + guideId + "\"")
                + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    /** 读取库内当前版本，供"提交最新版本"的用例使用，避免把版本冲突误当成别的冲突。 */
    private int currentVersion(Long departureId) {
        return departures.selectById(departureId).version;
    }

    private Long createDeparture(Long routeId, int maxPeople, String status) throws Exception {
        String response = mvc.perform(post("/api/admin/departures").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureBody(routeId, maxPeople, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long id = Long.valueOf(json.readTree(response).get("data").get("id").asString());
        if (!"DRAFT".equals(status)) {
            departures.update(null, new UpdateWrapper<Departure>().eq("id", id).set("status", status));
        }
        return id;
    }

    private Long insertRoute(String name) {
        TravelRoute route = new TravelRoute();
        route.name = name;
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 6;
        route.status = "PUBLISHED";
        route.ratingAvg = new BigDecimal("0.00");
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        return route.id;
    }

    private Long insertUser(String username, String nickname) {
        SysUser user = new SysUser();
        user.username = username;
        user.nickname = nickname;
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        user.createdAt = LocalDateTime.now();
        users.insert(user);
        return user.id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
