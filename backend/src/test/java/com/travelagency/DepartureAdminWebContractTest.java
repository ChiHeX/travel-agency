package com.travelagency;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 团期管理端点的 Web 层契约冒烟测试：<b>不需要数据库</b>，随普通 {@code mvn test} 一起执行。
 *
 * <p>覆盖不依赖数据库、但在真实运行中最容易出问题的部分：</p>
 * <ul>
 *   <li>路径与 HTTP 方法是否按契约注册（迁移到 AdminDepartureController 后不能出现 404/405）；</li>
 *   <li>未登录 401、普通用户 403 的权限行为；</li>
 *   <li>契约外的字段（{@code status} / {@code reservedPeople} / {@code confirmedPeople} /
 *       {@code version}）必须在进入业务逻辑之前被拒绝，否则客户端可以直接改写剩余名额与状态机；</li>
 *   <li>字段语义错误在访问数据库之前返回 422，并带可定位的 {@code errors} 列表；</li>
 *   <li>错误响应符合 docs/API.md 的统一信封结构。</li>
 * </ul>
 *
 * <p>需要数据库的业务规则（创建/修改落库、导游冲突、名额校验、状态级联）不在本类覆盖范围内：
 * 现有 {@code DepartureStateConcurrencyIntegrationTest} 与 {@code DepartureCapacityConcurrencyIntegrationTest}
 * 只覆盖导游端的出发/结束状态机与名额并发，后台团期 CRUD 的落库断言仍需要补一个
 * 由 {@code TRAVEL_MYSQL_TEST=true} 门控的集成测试。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class DepartureAdminWebContractTest {

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }
        return mvc;
    }

    /** 创建团期的合法请求体，各用例按需替换单个字段。 */
    private static final String VALID_BODY = """
            {"routeId": "1", "startDate": "2026-10-01", "endDate": "2026-10-06",
             "adultPrice": "2999.00", "childPrice": "1999.00", "maxPeople": 30}
            """;

    @Test
    void adminDepartureEndpointsRequireLogin() throws Exception {
        mvc().perform(get("/api/admin/departures"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc().perform(post("/api/admin/departures")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mvc().perform(patch("/api/admin/departures/1/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleWithoutStaffPermissionIsForbidden() throws Exception {
        mvc().perform(get("/api/admin/departures").with(user("plain").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /**
     * 契约 {@code DepartureUpsertRequest} 里没有 {@code status}：它必须被严格模式拒绝（400），
     * 而不是被静默忽略或写进数据库。
     */
    @Test
    void rejectsStatusFieldOutsideTheContract() throws Exception {
        mvc().perform(post("/api/admin/departures").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"maxPeople\": 30", "\"maxPeople\": 30, \"status\": \"OPEN\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /** 名额计数与版本号同样不在契约内：可写会让"剩余名额"直接失真。 */
    @Test
    void rejectsCapacityCountersOutsideTheContract() throws Exception {
        mvc().perform(post("/api/admin/departures").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"maxPeople\": 30",
                                "\"maxPeople\": 30, \"reservedPeople\": 0, \"confirmedPeople\": 0")))
                .andExpect(status().isBadRequest());

        mvc().perform(put("/api/admin/departures/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"maxPeople\": 30", "\"maxPeople\": 30, \"version\": 99")))
                .andExpect(status().isBadRequest());
    }

    /** 字段语义错误必须在访问数据库之前返回 422，并给出可定位的 errors 列表。 */
    @Test
    void fieldValidationFailsBeforeAnyDatabaseAccess() throws Exception {
        // 缺少必填字段（routeId / startDate / adultPrice …）
        mvc().perform(post("/api/admin/departures").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxPeople\": 30}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());

        // 最大人数小于 1
        mvc().perform(post("/api/admin/departures").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"maxPeople\": 30", "\"maxPeople\": 0")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("maxPeople"));

        // 金额为负
        mvc().perform(put("/api/admin/departures/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"adultPrice\": \"2999.00\"", "\"adultPrice\": \"-1.00\"")))
                .andExpect(status().isUnprocessableContent());

        // 金额精度超过 DECIMAL(12,2)：必须在写库前拦下，而不是变成 500
        mvc().perform(post("/api/admin/departures").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"adultPrice\": \"2999.00\"", "\"adultPrice\": \"2999.001\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("adultPrice"));
    }

    /** 状态端点只接受契约 DepartureStatus 枚举，非法值返回 422 且错误项指向 status。 */
    @Test
    void statusEndpointRejectsValueOutsideTheContractEnum() throws Exception {
        mvc().perform(patch("/api/admin/departures/1/status").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"NOT_A_STATUS\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));

        mvc().perform(patch("/api/admin/departures/1/status").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"\"}"))
                .andExpect(status().isUnprocessableContent());

        // PUBLISHED 是线路状态，不是团期状态：不能串用。
        mvc().perform(patch("/api/admin/departures/1/status").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    /** 列表 status 过滤参数只接受契约枚举，非法值必须先被拒绝而不是静默返回空列表。 */
    @Test
    void listRejectsStatusOutsideTheContractEnum() throws Exception {
        mvc().perform(get("/api/admin/departures").with(user("staff").roles("STAFF"))
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** 团期接口的路径与方法必须真实注册：请求体校验失败应返回 422 而不是 404/405。 */
    @Test
    void departureEndpointsAreRegisteredWithContractPaths() throws Exception {
        mvc().perform(post("/api/admin/departures").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());

        mvc().perform(put("/api/admin/departures/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());

        // 详情端点的路径注册：非数字的路径参数必须在进入业务逻辑前被判为参数类型错误（400）；
        // 若该路径没有注册，这里会拿到 404，因此该断言能真实区分"未注册"与"已注册"。
        mvc().perform(get("/api/admin/departures/abc").with(user("staff").roles("STAFF")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
