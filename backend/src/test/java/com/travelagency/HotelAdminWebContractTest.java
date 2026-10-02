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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 酒店资料端点（契约 {@code Admin Resources} 的 {@code /admin/hotels}）的 Web 层契约冒烟测试：
 * <b>不需要数据库</b>，随普通 {@code mvn test} 一起执行。
 *
 * <p>覆盖不依赖数据库、但在真实运行中最容易出问题的部分：</p>
 * <ul>
 *   <li>路径与 HTTP 方法是否按契约注册（从 {@code AdminController} 迁移到
 *       {@code AdminHotelController} 后不能出现 404/405）；</li>
 *   <li>未登录 401、普通用户 403 的权限行为；</li>
 *   <li>契约外字段必须在进入业务逻辑之前被拒绝 —— 早期以 {@code Hotel} 实体收发时，
 *       {@code id} / {@code createdAt} / {@code city} 都是可以提交的，客户端能指定主键与审计时间；</li>
 *   <li>必填、长度与坐标范围等字段语义错误在访问数据库之前返回 422，并带可定位的 {@code errors} 列表；</li>
 *   <li>列表 {@code keyword} 的契约长度上限（100 码点）在库外就被拒绝。</li>
 * </ul>
 *
 * <p>需要数据库的行为（创建 201 + {@code Location}、修改 404、删除 204/409 的真实落库结果）
 * 不在本类覆盖范围内，由 {@code HotelAdminContractIntegrationTest} 在
 * {@code TRAVEL_MYSQL_TEST=true} 时覆盖。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class HotelAdminWebContractTest {

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }
        return mvc;
    }

    /** 创建酒店的合法请求体，各用例按需替换单个字段。 */
    private static final String VALID_BODY = """
            {"name":"契约测试酒店","address":"云南省昆明市测试路 1 号","contactPhone":"087112345678",
             "longitude":102.832,"latitude":24.88,"intro":"演示简介",
             "dataSource":"团队测试数据","status":"ACTIVE"}
            """;

    /** 修改酒店的合法请求体：与建档相同，另带契约必填的乐观锁版本号。 */
    private static final String VALID_UPDATE_BODY = VALID_BODY.replace(
            "\"status\":\"ACTIVE\"}", "\"status\":\"ACTIVE\",\"version\":0}");

    @Test
    void adminHotelEndpointsRequireLogin() throws Exception {
        mvc().perform(get("/api/admin/hotels"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc().perform(post("/api/admin/hotels")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mvc().perform(put("/api/admin/hotels/1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_UPDATE_BODY))
                .andExpect(status().isUnauthorized());
        mvc().perform(delete("/api/admin/hotels/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleWithoutStaffPermissionIsForbidden() throws Exception {
        mvc().perform(get("/api/admin/hotels").with(user("plain").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc().perform(delete("/api/admin/hotels/1").with(user("plain").roles("USER")))
                .andExpect(status().isForbidden());
    }

    /**
     * 契约 {@code HotelCreateRequest} 里没有 {@code id} / {@code createdAt} / {@code updatedAt} /
     * {@code version}，也没有 {@code city}（城市是景点与地点指南的口径，酒店只有 {@code address}）：
     * 这些字段必须被严格模式拒绝（400），而不是被静默忽略或写进数据库。
     *
     * <p>{@code version} 尤其重要：建档的版本由服务端从 0 起算，客户端指定版本号没有意义，
     * 放行只会让"创建时也参与版本决定"这种语义混进契约。</p>
     */
    @Test
    void rejectsFieldsOutsideTheContract() throws Exception {
        for (String extra : new String[]{"\"id\":\"1\"", "\"createdAt\":\"2026-01-01T00:00:00+08:00\"",
                "\"updatedAt\":\"2026-01-01T00:00:00+08:00\"", "\"city\":\"昆明\"", "\"deleted\":0",
                "\"version\":0"}) {
            String body = VALID_BODY.replace("\"status\":\"ACTIVE\"", "\"status\":\"ACTIVE\"," + extra);
            mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    /**
     * 修改必须回传版本号：契约 {@code HotelUpdateRequest} 把 {@code version} 列为必填，
     * 缺字段按 422 处理，不能静默当成 0 —— 静默按 0 会把"忘了回传版本"变成一次必然的冲突
     * 或一次意外的覆盖。负数版本同样在库外被拒。
     */
    @Test
    void updateRequiresVersion() throws Exception {
        mvc().perform(put("/api/admin/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("version")));

        mvc().perform(put("/api/admin/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_BODY.replace("\"version\":0", "\"version\":-1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("version")));
    }

    /** 酒店接口的路径与方法必须真实注册：请求体校验失败应返回 422 而不是 404/405。 */
    @Test
    void hotelEndpointsAreRegisteredWithContractPaths() throws Exception {
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());

        mvc().perform(put("/api/admin/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());

        // 非数字的路径参数必须在进入业务逻辑前被判为参数类型错误（400）；
        // 若该路径没有注册，这里会拿到 404，因此该断言能真实区分"未注册"与"已注册"。
        mvc().perform(delete("/api/admin/hotels/abc").with(user("staff").roles("STAFF")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /**
     * 契约声明的是 GET/POST {@code /admin/hotels} 与 GET/PUT/DELETE {@code /admin/hotels/{hotelId}}；
     * {@code PATCH} 不在其中，必须被框架挡下（405），而不是悄悄落到某个实现上。
     *
     * <p>反过来说，这条也钉住了"迁移后没有残留旧映射"：若 {@code AdminController} 里那套
     * 遗留端点还在，同一路径上会存在重复映射，应用根本起不来。</p>
     */
    @Test
    void methodsOutsideTheContractAreNotMapped() throws Exception {
        // 契约里没有 PATCH /admin/hotels/{hotelId}（酒店状态通过 PUT 提交 status 修改）
        mvc().perform(patch("/api/admin/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isMethodNotAllowed());

        // 列表路径只接受 GET/POST
        mvc().perform(delete("/api/admin/hotels").with(user("staff").roles("STAFF")))
                .andExpect(status().isMethodNotAllowed());
    }

    /**
     * 详情端点（{@code GET /admin/hotels/{hotelId}}）：乐观锁冲突后前端必须能按主键取到
     * 服务器最新资料（含最新名称与版本号）。权限与路径注册同样按契约校验。
     */
    @Test
    void hotelDetailRequiresStaffAndIsRegistered() throws Exception {
        mvc().perform(get("/api/admin/hotels/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mvc().perform(get("/api/admin/hotels/1").with(user("plain").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 路径已注册：非数字路径参数应在进入业务逻辑前被判为参数类型错误（400），
        // 若未注册这里会是 404，因此该断言能真实区分"未注册"与"已注册"。
        mvc().perform(get("/api/admin/hotels/abc").with(user("staff").roles("STAFF")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /** 字段语义错误必须在访问数据库之前返回 422，并给出可定位的 errors 列表。 */
    @Test
    void fieldValidationFailsBeforeAnyDatabaseAccess() throws Exception {
        // 缺少契约必填字段（name / dataSource）
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"只有地址\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());

        // 只有空白的名称不算填写（@NotBlank），不能落成一家空白酒店
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"契约测试酒店\"", "\"   \"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("name")));

        // 名称超出契约 maxLength: 128
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"契约测试酒店\"", "\"" + "长".repeat(129) + "\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("name")));

        // 长度按 Unicode 码点计数：129 个 emoji 是 129 个码点（258 个码元），同样必须被拒。
        // 反向的"码元超限但码点合法必须放行"由 HotelAdminContractIntegrationTest 覆盖。
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"契约测试酒店\"", "\"" + "😀".repeat(129) + "\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("name")));

        // 地址超出契约 maxLength: 255
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"云南省昆明市测试路 1 号\"", "\"" + "路".repeat(256) + "\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("address")));

        // 联系电话超出契约 maxLength: 20
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"087112345678\"", "\"" + "1".repeat(21) + "\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("contactPhone")));

        // 简介超出契约 maxLength: 10000
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"演示简介\"", "\"" + "介".repeat(10001) + "\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("intro")));

        // 数据来源说明是契约必填项，漏填不能默认成"未知来源"
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"dataSource\":\"团队测试数据\",", "")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("dataSource")));

        // 坐标超出契约 Longitude / Latitude 的范围
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"longitude\":102.832", "\"longitude\":181")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("longitude")));

        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"latitude\":24.88", "\"latitude\":-91")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("latitude")));

        // 状态只接受契约 AccountStatus 枚举，不能用 1/0 或其它取值
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"status\":\"ACTIVE\"", "\"status\":\"BANNED\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("status")));

        // 修改端点同样校验：PUT 不是"写入即可"，非法坐标也要在库外被拒
        mvc().perform(put("/api/admin/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_BODY.replace("\"longitude\":102.832", "\"longitude\":-180.5")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("longitude")));

        // 经纬度必须成对（契约 HotelCreate/UpdateRequest 的 dependentRequired）：只填一个的坐标
        // 在地图上无法落点、会被静默丢弃，建档与修改都必须以 422 拦下。
        for (String half : new String[]{
                VALID_BODY.replace("\"latitude\":24.88", "\"latitude\":null"),
                VALID_BODY.replace("\"longitude\":102.832", "\"longitude\":null")}) {
            mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                            .contentType(MediaType.APPLICATION_JSON).content(half))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value(endsWith("longitude")))
                    .andExpect(jsonPath("$.errors[0].message").value("经度和纬度需要同时填写，或同时留空"));
        }

        mvc().perform(put("/api/admin/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_BODY.replace("\"latitude\":24.88", "\"latitude\":null")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("经度和纬度需要同时填写，或同时留空"));
    }

    /**
     * 列表 {@code keyword} 的契约长度上限（100 码点）必须在库外就被拒绝：
     * 该参数会被拼进 {@code LIKE %…%}，超长关键字会放大查询代价。
     */
    @Test
    @DisplayName("列表 keyword 超长在库外即被拒绝：100 码点通过、101 码点 422")
    void listRejectsKeywordOverTheContractLimit() throws Exception {
        mvc().perform(get("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .param("page", "1").param("size", "5").param("keyword", "关".repeat(100)))
                .andExpect(status().isOk());

        mvc().perform(get("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .param("page", "1").param("size", "5").param("keyword", "k".repeat(101)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].message").value("keyword 长度不能超过 100 个字符"));
    }
}
