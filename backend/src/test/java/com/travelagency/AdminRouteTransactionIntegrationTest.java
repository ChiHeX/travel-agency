package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.OperationLogMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「业务写入与操作日志必须同一事务」的数据库级回归测试（P2）。
 *
 * <p>这组用例<b>故意不加 {@code @Transactional}</b>：只有让 service 的事务真正提交/回滚，
 * 才能验证"日志写失败时线路没有被创建"。因此测试自己在 {@link #cleanUp()} 里清理数据。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class AdminRouteTransactionIntegrationTest {

    private static final String NAME_PREFIX = "事务回归线路-";

    @Autowired WebApplicationContext context;
    @Autowired TravelRouteMapper routes;
    @Autowired RouteItineraryDayMapper days;
    @Autowired RouteItineraryItemMapper items;
    @Autowired OperationLogMapper operationLogs;
    @Autowired SysUserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    /** 替换操作日志记录器，用于模拟"日志写入失败"。 */
    @MockitoSpyBean OperationLogRecorder operationLogRecorder;

    private MockMvc mvc;
    private String token;
    private SysUser actor;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        actor = account();
        token = "Bearer " + tokens.createToken(actor.id, actor.username, Set.of("STAFF"));
    }

    @AfterEach
    void cleanUp() {
        List<TravelRoute> created = routes.selectList(
                new QueryWrapper<TravelRoute>().likeRight("name", NAME_PREFIX));
        for (TravelRoute route : created) {
            List<RouteItineraryDay> routeDays = days.selectList(
                    new QueryWrapper<RouteItineraryDay>().eq("route_id", route.id));
            for (RouteItineraryDay day : routeDays) {
                items.delete(new QueryWrapper<RouteItineraryItem>().eq("day_id", day.id));
            }
            days.delete(new QueryWrapper<RouteItineraryDay>().eq("route_id", route.id));
            operationLogs.delete(new QueryWrapper<OperationLog>().eq("object_id", String.valueOf(route.id)));
            routes.deleteById(route.id);
        }
        if (actor != null) {
            users.deleteById(actor.id);
        }
    }

    @Test
    void operationLogIsWrittenInTheSameTransactionAsTheRoute() throws Exception {
        String name = NAME_PREFIX + suffix();
        var response = mvc.perform(post("/api/admin/routes").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        String routeId = json.readTree(response.getContentAsString()).get("data").get("id").asString();

        List<OperationLog> logs = operationLogs.selectList(new QueryWrapper<OperationLog>()
                .eq("object_type", "ROUTE").eq("object_id", routeId));
        assertEquals(1, logs.size(), "创建线路应当写入一条操作日志");
        OperationLog log = logs.get(0);
        assertEquals("线路", log.module);
        assertEquals("CREATE", log.operationType);
        assertEquals(actor.id, log.operatorId, "操作人必须是当前登录账号");
    }

    @Test
    void createRouteIsRolledBackWhenOperationLogWriteFails() throws Exception {
        String name = NAME_PREFIX + suffix();
        // 模拟 operation_log 写入失败（例如磁盘/权限/表不可写）
        doThrow(new RuntimeException("operation_log unavailable"))
                .when(operationLogRecorder).record(any(), any(), any(), any(), any(), any());

        mvc.perform(post("/api/admin/routes").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(name)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        // 关键断言：接口报错后数据库里不能残留这条线路，否则用户重试就会创建重复线路。
        assertEquals(0L, routes.selectCount(new QueryWrapper<TravelRoute>().eq("name", name)),
                "日志失败时线路创建必须一并回滚");
        assertTrue(operationLogs.selectCount(new QueryWrapper<OperationLog>()
                .eq("object_type", "ROUTE").eq("detail", "创建线路：" + name)) == 0L,
                "失败的请求不应留下操作日志");
    }

    private static String body(String name) {
        return """
                {"name": "%s", "departureCity": "上海", "destination": "云南", "durationDays": 3}
                """.formatted(name);
    }

    private SysUser account() {
        SysUser user = new SysUser();
        user.username = "tx_test_" + suffix();
        user.nickname = "Transaction test";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
