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
 *       {@code id} / {@code createdAt} 都是可以提交的，客户端能指定主键与审计时间；</li>
 *   <li>必填、长度与坐标范围等字段语义错误在访问数据库之前返回 422，并带可定位的 {@code errors} 列表；</li>
 *   <li>本次契约新增的酒店资料字段（{@code city} / {@code coverUrl} / {@code images} /
 *       {@code starRating} / {@code facilities} / {@code checkInTime} / {@code checkOutTime}）
 *       的取值域同样在库外被拦下，且契约允许的取值不会被误判；</li>
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

    /**
     * 合法请求体里那一张图片（契约 {@code HotelImageUpsert}，必填 {@code url} / {@code sortOrder}）。
     * 图片相关的用例整段替换它，而不是往字符串里塞碎片。
     */
    private static final String VALID_IMAGES =
            "[{\"url\":\"https://cdn.example.com/hotels/1.jpg\",\"alt\":\"酒店外观\",\"sortOrder\":1}]";

    /**
     * 创建酒店的合法请求体，各用例按需替换单个字段。
     *
     * <p>字段覆盖本次契约新增的全部内容（{@code city} / {@code coverUrl} / {@code images} /
     * {@code starRating} / {@code facilities} / {@code checkInTime} / {@code checkOutTime}）：
     * 这些取值本身必须能通过校验，否则"合法请求"的基准就不存在了。</p>
     */
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

    /**
     * 契约 {@code HotelCreateRequest} 里没有 {@code id} / {@code createdAt} / {@code updatedAt} /
     * {@code version}：这些字段必须被严格模式拒绝（400），而不是被静默忽略或写进数据库。
     *
     * <p>{@code city} 曾经也在这份名单里（那时酒店只有 {@code address}）；本次契约把
     * {@code city} 收进了 {@code HotelCreateRequest}（必填，见 {@code docs/API.md} §12.2），
     * 因此它不再是"契约外字段"，而是由专门用例校验的必填字段。</p>
     *
     * <p>{@code version} 尤其重要：建档的版本由服务端从 0 起算，客户端指定版本号没有意义，
     * 放行只会让"创建时也参与版本决定"这种语义混进契约。</p>
     */
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

    /**
     * 本次契约新增的酒店资料字段的取值域校验（{@code docs/openapi.yaml} 的
     * {@code HotelCreateRequest} / {@code HotelUpdateRequest}）。
     *
     * <p>逐条对应"库内列拦不住"的语义错误：城市为空 → 这条资料在按城市筛选的后台列表里永远找不到，
     * 用户端卡片也没有城市可显示；星级 0 或 6 → 用户端画不出星级；{@code 9:00} / {@code 24:00} /
     * {@code 12:60} 都不是契约 {@code ClockTime} 的 24 小时制 {@code HH:mm}；
     * {@code javascript:} 开头的地址会被浏览器当脚本执行，而这些图片渲染在<b>无需登录</b>的用户端页面上；
     * 超过 10 张图片与缺失 {@code sortOrder} 会让展示顺序无从确定；
     * 枚举外的设施标签会让用户端找不到对应图标，只能显示一个无法解释的字符串。</p>
     *
     * <p>全部要求在访问数据库之前完成 —— 这类请求不该先占一次连接池与事务再报错。</p>
     */
    @Test
    @DisplayName("新增酒店：city/星级/入住退房时刻/封面/图片/设施的非法取值在库外 422，且可定位")
    void newProfileFieldsAreValidatedBeforeAnyDatabaseAccess() throws Exception {
        // city 是本次新增的必填项（破坏性变更，见 docs/API.md §12.2）：
        // 留空与少一个字段都必须被拦下，不能等到写库时靠 NOT NULL 报 500。
        assertFieldRejected(VALID_BODY.replace("\"city\":\"昆明\"", "\"city\":\"   \""), endsWith("city"));
        assertFieldRejected(VALID_BODY.replace("\"city\":\"昆明\",", ""), endsWith("city"));

        // 官方星级只接受 1~5：0 与 6 都是"填了一个不存在的星级"。
        assertFieldRejected(VALID_BODY.replace("\"starRating\":4", "\"starRating\":0"), endsWith("starRating"));
        assertFieldRejected(VALID_BODY.replace("\"starRating\":4", "\"starRating\":6"), endsWith("starRating"));

        // 入住 / 退房时刻：9:00 少了补零、24:00 与 12:60 都不是真实时刻，
        // 它们各自还能被解释成不同的含义，因此不接受"看起来像时间"的写法。
        assertFieldRejected(VALID_BODY.replace("\"checkInTime\":\"14:00\"", "\"checkInTime\":\"9:00\""),
                endsWith("checkInTime"));
        assertFieldRejected(VALID_BODY.replace("\"checkInTime\":\"14:00\"", "\"checkInTime\":\"24:00\""),
                endsWith("checkInTime"));
        assertFieldRejected(VALID_BODY.replace("\"checkOutTime\":\"12:00\"", "\"checkOutTime\":\"12:60\""),
                endsWith("checkOutTime"));

        // 封面地址只接受 http / https 绝对地址：javascript: 会被浏览器执行、相对路径在其它域名下
        // 必然 404，而服务端没有第二次机会再检查它。
        assertFieldRejected(
                VALID_BODY.replace("\"https://cdn.example.com/hotels/cover.jpg\"", "\"javascript:alert(1)\""),
                endsWith("coverUrl"));

        // 图片数量上限 10：一次详情页塞几十张图会把响应体吹大，也没有页面能展示完。
        assertFieldRejected(VALID_BODY.replace(VALID_IMAGES, images(11)), endsWith("images"));

        // 每张图片自身：地址必须是 http(s)，排序号必须提交（缺排序号等于展示顺序由数据库决定）。
        assertFieldRejected(VALID_BODY.replace(VALID_IMAGES,
                "[{\"url\":\"ftp://cdn.example.com/hotels/1.jpg\",\"sortOrder\":1}]"), endsWith("images[0].url"));
        assertFieldRejected(VALID_BODY.replace(VALID_IMAGES,
                "[{\"url\":\"https://cdn.example.com/hotels/1.jpg\"}]"), endsWith("images[0].sortOrder"));

        // 设施标签必须是契约 HotelFacility 枚举内的取值：用户端按标签渲染图标与名称，
        // 自由文本会变成一堆需要人工归一的同义词。
        assertFieldRejected(VALID_BODY.replace("\"facilities\":[\"WIFI\"]", "\"facilities\":[\"FREE_WIFI\"]"),
                containsString("facilities"));
        assertFieldRejected(VALID_BODY.replace("\"facilities\":[\"WIFI\"]", "\"facilities\":[\"\"]"),
                containsString("facilities"));

        // 修改端点的必填口径必须与建档一致，否则前端会在"保存"时才被打回。
        mvc().perform(put("/api/admin/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_BODY.replace("\"city\":\"昆明\"", "\"city\":\"   \"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("city")));
    }

    /**
     * 契约 {@code facilities} 的 {@code uniqueItems: true} 由服务层
     * （{@code HotelFacility.normalize}）判定，因此重复标签同样是 422 {@code VALIDATION_ERROR}，
     * 只是它<b>不是</b>字段级校验错误：统一信封里的 {@code errors} 为空，定位信息在 {@code message} 中
     * （"酒店设施标签不能重复：WIFI"）。
     *
     * <p>为什么不能静默去重：重复项通常意味着前端把数据拼错了，悄悄"帮它成功"会让调用方
     * 以为提交什么都能过，而这个错误永远暴露不出来。</p>
     *
     * <p>这条规则在服务层（写库之前）判定，请求必须真的走进控制器，因此这里用
     * {@link #STAFF_PRINCIPAL} 而不是 {@code user("staff")}：后者的主体不是
     * {@link UserPrincipal}，会在控制器里被判成未登录（401），用例就测不到重复标签了。
     * 该请求仍然不碰数据库 —— 重复校验发生在写入之前。</p>
     */
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

    /**
     * 反向断言：契约允许的取值必须真的被接受。
     *
     * <p>只测"非法被拒"是不够的 —— 校验写严了（例如把 {@code 14:00} 也判错、把一张图片判成超限）
     * 同样是违约，而且调用方无从规避。这里用完整合法请求体提交一次：只要结果<b>不是</b> 422，
     * 就说明这些字段的取值域判对了。</p>
     *
     * <p>为什么不断言具体的成功码：本类只负责"字段语义在库外判对了"这一层，
     * 合法请求继续往下走会碰到与本用例无关的分支 —— 本类用 {@code user("staff")} 提供的身份
     * 在控制器入口就会被 {@code CurrentUser.required()} 判成未登录（401），
     * 没有数据库时则会在 MyBatis 处失败。这两种结果都说明"校验放行了"，
     * 而唯一的反面（422）恰好就是本用例要挡住的"契约允许、实现拒绝"。</p>
     */
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

    // ===================== 断言工具 =====================

    /**
     * 后台工作人员身份。
     *
     * <p>{@code CurrentUser.required()} 只承认 {@link UserPrincipal} 这一种主体类型，而
     * {@code user("staff").roles("STAFF")} 给出的主体是 Spring Security 自带的 {@code User}：
     * 用它提交一个"能通过字段校验"的请求，会在控制器里被当成未登录而回 401。
     * 因此凡是需要请求真的进入服务层的用例，都必须用这里的身份。</p>
     */
    private static final UserPrincipal STAFF_PRINCIPAL =
            new UserPrincipal(1L, "contract-staff", List.of("STAFF"), true);

    /**
     * 断言该请求体在库外被判为 422 {@code VALIDATION_ERROR}，且 {@code errors[0]} 能定位到出错字段。
     *
     * @param expectedField 字段定位的匹配器：路径固定的字段用 {@code endsWith("city")}；
     *                      落在集合元素上的约束（例如 {@code facilities[0]}）路径由校验器生成，
     *                      用 {@code containsString} 匹配字段名部分更稳，也更贴近"能定位到哪个字段"这个诉求。
     */
    private void assertFieldRejected(String body, Matcher<String> expectedField) throws Exception {
        mvc().perform(post("/api/admin/hotels").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").value(expectedField));
    }

    /** 生成 {@code count} 张合法图片的 JSON 数组（用于 {@code images.maxItems} 的上限断言）。 */
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
