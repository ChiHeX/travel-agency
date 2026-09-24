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
 * 线路管理端点的 Web 层契约冒烟测试：<b>不需要数据库</b>，随普通 {@code mvn test} 一起执行。
 *
 * <p>覆盖不依赖数据库、但在真实运行中最容易出问题的部分：</p>
 * <ul>
 *   <li>路径与 HTTP 方法是否按契约注册（Migrate 到 AdminRouteController 后不能出现 404/405）；</li>
 *   <li>未登录 401、普通用户 403 的权限行为；</li>
 *   <li>请求校验是否在访问数据库之前发生（未知字段 400、字段语义错误 422）；</li>
 *   <li>错误响应是否符合 docs/API.md 的统一信封结构。</li>
 * </ul>
 *
 * <p>需要数据库的业务规则（创建/修改/上下架、行程 CRUD、级联删除等）由
 * {@link RouteAdminContractIntegrationTest} 覆盖，它需要设置 TRAVEL_MYSQL_TEST=true。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class RouteAdminWebContractTest {

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }
        return mvc;
    }

    @Test
    void adminRouteEndpointsRequireLogin() throws Exception {
        mvc().perform(get("/api/admin/routes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc().perform(get("/api/admin/routes/1/itinerary-days"))
                .andExpect(status().isUnauthorized());
        mvc().perform(post("/api/admin/routes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleWithoutStaffPermissionIsForbidden() throws Exception {
        mvc().perform(get("/api/admin/routes").with(user("plain").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /** 未知字段必须在进入业务逻辑之前被拒绝（400），避免客户端提交 status 等字段。 */
    @Test
    void unknownFieldIsRejectedBeforeBusinessLogic() throws Exception {
        mvc().perform(post("/api/admin/routes").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "线路名称", "departureCity": "上海", "destination": "云南",
                                 "durationDays": 3, "status": "PUBLISHED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /** 字段语义错误必须在访问数据库之前返回 422，并给出可定位的 errors 列表。 */
    @Test
    void fieldValidationFailsBeforeAnyDatabaseAccess() throws Exception {
        mvc().perform(post("/api/admin/routes").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "x", "departureCity": "", "destination": "云南", "durationDays": 0}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());

        mvc().perform(put("/api/admin/routes/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"线路名称\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc().perform(patch("/api/admin/routes/1/status").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    /** status 过滤参数只接受契约枚举，非法值必须先被拒绝而不是静默返回空列表。 */
    @Test
    void listRejectsStatusOutsideTheContractEnum() throws Exception {
        mvc().perform(get("/api/admin/routes").with(user("staff").roles("STAFF"))
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** 行程接口的路径与方法必须真实注册：请求体校验失败应返回 422 而不是 404/405。 */
    @Test
    void itineraryEndpointsAreRegisteredWithContractPaths() throws Exception {
        mvc().perform(post("/api/admin/routes/1/itinerary-days").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dayNumber\":0,\"title\":\"\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc().perform(put("/api/admin/itinerary-days/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dayNumber\":1,\"title\":\"\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc().perform(post("/api/admin/itinerary-days/1/items").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortNo\":1,\"itemType\":\"SHOPPING\",\"name\":\"购物\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc().perform(put("/api/admin/itinerary-items/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortNo\":1,\"itemType\":\"ATTRACTION\",\"name\":\"景点\",\"longitude\":181}"))
                .andExpect(status().isUnprocessableContent());
    }
}
