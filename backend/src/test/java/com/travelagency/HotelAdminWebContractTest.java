package com.travelagency;

import com.travelagency.common.security.UserPrincipal;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    private static final String VALID_IMAGES =
            "[{\"url\":\"https://cdn.example.com/hotels/1.jpg\",\"alt\":\"酒店外观\",\"sortOrder\":1}]";

    private static final String VALID_BODY =
            "{\"name\":\"契约测试酒店\",\"city\":\"昆明\","
                    + "\"address\":\"云南省昆明市测试路 1 号\",\"contactPhone\":\"087112345678\","
                    + "\"coverUrl\":\"https://cdn.example.com/hotels/cover.jpg\","
                    + "\"images\":" + VALID_IMAGES + ","
                    + "\"starRating\":4,\"facilities\":[\"WIFI\"],"
                    + "\"checkInTime\":\"14:00\",\"checkOutTime\":\"12:00\","
                    + "\"longitude\":102.832,\"latitude\":24.88,\"intro\":\"演示简介\","
                    + "\"dataSource\":\"团队测试数据\",\"status\":\"ACTIVE\"}";

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

    @Test
    void rejectsFieldsOutsideTheContract() throws Exception {
        for (String extra : new String[]{"\"id\":\"1\"", "\"createdAt\":\"2026-01-01T00:00:00+08:00\"",
                "\"updatedAt\":\"2026-01-01T00:00:00+08:00\"", "\"deleted\":0",
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

        // 经纬度必须成对（契约 CoordinatePairRule）：只填一个的坐标
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

    @Test
    @DisplayName("新增酒店：city/星级/入住退房时刻/封面/图片/设施的非法取值在库外 422，且可定位")
    void newProfileFieldsAreValidatedBeforeAnyDatabaseAccess() throws Exception {
        assertFieldRejected(VALID_BODY.replace("\"city\":\"昆明\"", "\"city\":\"   \""), endsWith("city"));
        assertFieldRejected(VALID_BODY.replace("\"city\":\"昆明\",", ""), endsWith("city"));

        assertFieldRejected(VALID_BODY.replace("\"starRating\":4", "\"starRating\":0"), endsWith("starRating"));
        assertFieldRejected(VALID_BODY.replace("\"starRating\":4", "\"starRating\":6"), endsWith("starRating"));

        assertFieldRejected(VALID_BODY.replace("\"checkInTime\":\"14:00\"", "\"checkInTime\":\"9:00\""),
                endsWith("checkInTime"));
        assertFieldRejected(VALID_BODY.replace("\"checkInTime\":\"14:00\"", "\"checkInTime\":\"24:00\""),
                endsWith("checkInTime"));
        assertFieldRejected(VALID_BODY.replace("\"checkOutTime\":\"12:00\"", "\"checkOutTime\":\"12:60\""),
                endsWith("checkOutTime"));

        assertFieldRejected(
                VALID_BODY.replace("\"https://cdn.example.com/hotels/cover.jpg\"", "\"javascript:alert(1)\""),
                endsWith("coverUrl"));

        assertFieldRejected(VALID_BODY.replace(VALID_IMAGES, images(11)), endsWith("images"));

        assertFieldRejected(VALID_BODY.replace(VALID_IMAGES,
                "[{\"url\":\"ftp://cdn.example.com/hotels/1.jpg\",\"sortOrder\":1}]"), endsWith("images[0].url"));
        assertFieldRejected(VALID_BODY.replace(VALID_IMAGES,
                "[{\"url\":\"https://cdn.example.com/hotels/1.jpg\"}]"), endsWith("images[0].sortOrder"));

        assertFieldRejected(VALID_BODY.replace("\"facilities\":[\"WIFI\"]", "\"facilities\":[\"FREE_WIFI\"]"),
                containsString("facilities"));
        assertFieldRejected(VALID_BODY.replace("\"facilities\":[\"WIFI\"]", "\"facilities\":[\"\"]"),
                containsString("facilities"));

        mvc().perform(put("/api/admin/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_BODY.replace("\"city\":\"昆明\"", "\"city\":\"   \"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("city")));
    }

    @Test
    @DisplayName("新增酒店：facilities 重复标签是 422（uniqueItems），message 指出重复的标签")
    void duplicateFacilitiesAreRejected() throws Exception {
        mvc().perform(post("/api/admin/hotels").with(user(STAFF_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"facilities\":[\"WIFI\"]",
                                "\"facilities\":[\"WIFI\",\"WIFI\"]")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.message").value(containsString("WIFI")));
    }

    @Test
    @DisplayName("新增酒店：合法的新字段组合（city/1 张图片/WIFI/4 星/14:00）不得被判成 422")
    void legalProfilePayloadPassesFieldValidation() throws Exception {
        MvcResult result = mvc()
                .perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertTrue(status != 422,
                "契约允许的取值不得被判成 422 字段校验失败，实际状态码 " + status
                        + "，响应体 " + result.getResponse().getContentAsString());
    }

    private static final UserPrincipal STAFF_PRINCIPAL =
            new UserPrincipal(1L, "contract-staff", List.of("STAFF"), true);

    private void assertFieldRejected(String body, Matcher<String> expectedField) throws Exception {
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").value(expectedField));
    }

    private static String images(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 1; index <= count; index++) {
            if (index > 1) {
                json.append(',');
            }
            json.append("{\"url\":\"https://cdn.example.com/hotels/").append(index)
                    .append(".jpg\",\"sortOrder\":").append(index).append('}');
        }
        return json.append(']').toString();
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
