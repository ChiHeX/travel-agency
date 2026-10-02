package com.travelagency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 景点管理端点（契约 {@code Admin Resources} 的 {@code /admin/attractions}）的 Web 层契约冒烟测试：
 * <b>不需要数据库</b>，随普通 {@code mvn test} 一起执行。
 *
 * <p>覆盖不依赖数据库、但在真实运行中最容易出问题的部分：</p>
 * <ul>
 *   <li>路径与 HTTP 方法是否按契约注册（迁移到 {@code AdminAttractionController} 后不能出现 404/405）；</li>
 *   <li>未登录 401、普通用户 403 的权限行为；</li>
 *   <li>契约外字段（{@code id} / {@code createdAt} / {@code updatedAt}）必须在进入业务逻辑之前被拒绝，
 *       否则客户端可以直接指定主键与审计时间；</li>
 *   <li>必填、长度与坐标范围等字段语义错误在访问数据库之前返回 422，并带可定位的 {@code errors} 列表；</li>
 *   <li>列表 {@code keyword} 的契约长度上限（100 码点）在库外就被拒绝。</li>
 * </ul>
 *
 * <p>需要数据库的行为（创建 201 + {@code Location}、修改 404、删除 204/409 的真实落库结果）
 * 不在本类覆盖范围内，由 {@code AttractionAdminContractIntegrationTest} 在
 * {@code TRAVEL_MYSQL_TEST=true} 时覆盖。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class AttractionAdminWebContractTest {

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }
        return mvc;
    }

    /** 创建景点的合法请求体，各用例按需替换单个字段。 */
    private static final String VALID_BODY = """
            {"name":"契约测试景点","city":"大理","address":"云南省大理市",
             "longitude":100.165,"latitude":25.694,"intro":"演示简介",
             "dataSource":"团队测试数据","status":"ACTIVE"}
            """;

    @Test
    void adminAttractionEndpointsRequireLogin() throws Exception {
        mvc().perform(get("/api/admin/attractions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc().perform(post("/api/admin/attractions")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mvc().perform(put("/api/admin/attractions/1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mvc().perform(delete("/api/admin/attractions/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleWithoutStaffPermissionIsForbidden() throws Exception {
        mvc().perform(get("/api/admin/attractions").with(user("plain").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc().perform(delete("/api/admin/attractions/1").with(user("plain").roles("USER")))
                .andExpect(status().isForbidden());
    }

    /**
     * 契约 {@code AttractionUpsertRequest} 里没有 {@code id} / {@code createdAt} / {@code updatedAt}：
     * 它们必须被严格模式拒绝（400），而不是被静默忽略或写进数据库。
     *
     * <p>早期实现直接以 {@code Attraction} 实体接收请求体，这三个字段都是可提交的，
     * 客户端可以指定主键、伪造审计时间。</p>
     */
    @Test
    void rejectsFieldsOutsideTheContract() throws Exception {
        for (String extra : new String[]{"\"id\":\"1\"", "\"createdAt\":\"2026-01-01T00:00:00+08:00\"",
                "\"updatedAt\":\"2026-01-01T00:00:00+08:00\"", "\"deleted\":0"}) {
            String body = VALID_BODY.replace("\"status\":\"ACTIVE\"", "\"status\":\"ACTIVE\"," + extra);
            mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    /** 景点接口的路径与方法必须真实注册：请求体校验失败应返回 422 而不是 404/405。 */
    @Test
    void attractionEndpointsAreRegisteredWithContractPaths() throws Exception {
        mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());

        mvc().perform(put("/api/admin/attractions/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());

        // 非数字的路径参数必须在进入业务逻辑前被判为参数类型错误（400）；
        // 若该路径没有注册，这里会拿到 404，因此该断言能真实区分"未注册"与"已注册"。
        mvc().perform(delete("/api/admin/attractions/abc").with(user("staff").roles("STAFF")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /** 字段语义错误必须在访问数据库之前返回 422，并给出可定位的 errors 列表。 */
    @Test
    void fieldValidationFailsBeforeAnyDatabaseAccess() throws Exception {
        // 缺少契约必填字段（name / city / dataSource）
        mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"只有地址\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());

        // 只有空白的名称不算填写（@NotBlank），不能落成一个空白景点
        mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"契约测试景点\"", "\"   \"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("name")));

        // 名称超出契约 maxLength: 128
        mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"契约测试景点\"", "\"" + "长".repeat(129) + "\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("name")));

        // 城市超出契约 maxLength: 64
        mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"大理\"", "\"" + "城".repeat(65) + "\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("city")));

        // 数据来源说明是契约必填项，漏填不能默认成"未知来源"
        mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"dataSource\":\"团队测试数据\",", "")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("dataSource")));

        // 坐标超出契约 Longitude / Latitude 的范围
        mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"longitude\":100.165", "\"longitude\":181")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("longitude")));

        mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"latitude\":25.694", "\"latitude\":-91")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("latitude")));

        // 经纬度必须成对（契约 AttractionUpsertRequest 的 dependentRequired）：只填一个的坐标
        // 在地图上无法落点、会被静默丢弃，必须在写库前就以 422 拦下，并给出可定位的错误。
        for (String half : new String[]{
                VALID_BODY.replace("\"latitude\":25.694", "\"latitude\":null"),
                VALID_BODY.replace("\"longitude\":100.165", "\"longitude\":null")}) {
            mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                            .contentType(MediaType.APPLICATION_JSON).content(half))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value(endsWith("longitude")))
                    .andExpect(jsonPath("$.errors[0].message").value("经度和纬度需要同时填写，或同时留空"));
        }

        // 状态只接受契约 AccountStatus 枚举，不能用 1/0 或其它取值
        mvc().perform(post("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"status\":\"ACTIVE\"", "\"status\":\"BANNED\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("status")));

        // 修改端点同样校验：PUT 不是"写入即可"，非法坐标也要在库外被拒
        mvc().perform(put("/api/admin/attractions/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"longitude\":100.165", "\"longitude\":-180.5")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("longitude")));
    }

    /**
     * 列表 {@code keyword} 的契约长度上限（100 码点）必须在库外就被拒绝：
     * 该参数会被拼进 {@code LIKE %…%}，超长关键字会放大查询代价。
     */
    @Test
    void listRejectsKeywordOverTheContractLimit() throws Exception {
        mvc().perform(get("/api/admin/attractions").with(user("staff").roles("STAFF"))
                        .param("page", "1").param("size", "5").param("keyword", "k".repeat(101)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].message").value("keyword 长度不能超过 100 个字符"));
    }
}
