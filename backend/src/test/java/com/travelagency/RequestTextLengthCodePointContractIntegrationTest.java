package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 请求体文本长度的口径回归：一律按 Unicode <b>码点</b>计数，与契约的 JSON Schema
 * {@code maxLength} 一致，而不是 Java {@code @Size} 默认的 UTF-16 <b>码元</b>。
 *
 * <p><b>为什么需要这个类</b>：一个 emoji（如 U+1F600）是 1 个码点却是 2 个码元。
 * 用 {@code @Size(max = N)} 校验时，「码元超限、码点合法」的内容会被判成超长回 422 ——
 * 而这份内容契约明确允许、数据库列宽（utf8mb4 下 {@code VARCHAR(N)} 就是 N 个码点）
 * 也存得下。调用方（前端表单、导入脚本）无从规避，只能把合法的输入改短。</p>
 *
 * <p>本类钉住两件事，缺一不可：</p>
 * <ul>
 *   <li><b>边界内必须放行</b>：恰好 {@code max} 个码点的 emoji 内容要能真的写进数据库
 *       并原样读回 —— 这是 {@code @CodePointLength} 与 {@code @Size} 唯一能被观测到的差别，
 *       也是本类存在的理由；</li>
 *   <li><b>边界外必须拒绝</b>：{@code max + 1} 个码点仍然要 422，且 {@code errors[]}
 *       能指回出错字段，不能把「按码点算」做成「不校验」。</li>
 * </ul>
 *
 * <p><b>为什么走真库 + 真 HTTP 层</b>：纯 Mockito 单测绕过了参数校验、Jackson 绑定与序列化链，
 * 恰好跳过本类要保护的环节；而且「存得下」这件事只有在真库上写一遍并读回才能证明
 * （列宽不足时会因严格模式报 {@code Data too long}）。</p>
 *
 * <p><b>顺序约定</b>：每个用例先跑预期成功的调用，再跑预期 422 的调用。断言失败时消息里带上
 * 端点与字段名，避免"四个端点里的哪一个挂了"需要靠猜。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。整个类在事务内执行，结束时统一回滚。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        "app.integrations.alipay.callback-secret=codepoint-length-callback-secret-32-bytes"
})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class RequestTextLengthCodePointContractIntegrationTest {

    /** 一个 emoji 的码点表示：1 个码点 / 2 个 UTF-16 码元。 */
    private static final String EMOJI = "\uD83D\uDE00";

    /** 码点上限内、但码元数已超限的内容：这正是 @Size 会误拒的形态。 */
    private static String emojiOf(int codePoints) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codePoints; i++) {
            sb.append(EMOJI);
        }
        return sb.toString();
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    SysUserMapper users;

    @Autowired
    JwtTokenProvider tokens;

    @Autowired
    JsonMapper json;

    private MockMvc mvc;
    private SysUser admin;
    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = account();
        adminToken = bearer(admin, "ADMIN");
        userToken = bearer(account(), "USER");
    }

    // ------------------------------------------------------------------
    // 景点：name ≤ 128、city ≤ 64、dataSource ≤ 500（AdminAttractionController）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建景点：128 码点 / 256 码元的名称被接受并原样落库，129 码点判 422")
    void attractionAcceptsCodePointBoundedEmojiName() throws Exception {
        String name = emojiOf(128);
        String city = emojiOf(64);
        String dataSource = emojiOf(500);
        assertTrue(name.length() > 128, "前提：该名称的 UTF-16 码元数已超过契约上限，@Size 会误拒");
        assertEquals(128, name.codePointCount(0, name.length()), "前提：码点数在契约上限之内");

        JsonNode created = data(mvc.perform(post("/api/admin/attractions").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"city\":\"" + city + "\","
                                + "\"dataSource\":\"" + dataSource + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());

        // 必须原样返回、原样落库：写库时被截断同样是违约
        assertEquals(name, created.get("name").asString(), "景点名称必须原样返回");
        assertEquals(city, created.get("city").asString(), "所属城市必须原样返回");
        assertEquals(dataSource, created.get("dataSource").asString(), "数据来源说明必须原样返回");

        // 读回一次，确认库内没有被静默截断
        String reloaded = mvc.perform(get("/api/admin/attractions/" + created.get("id").asString())
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String reloadedName = json.readTree(reloaded).get("data").get("name").asString();
        assertEquals(name, reloadedName, "库内名称必须与提交内容逐字符一致，不能被截断");
        assertEquals(128, reloadedName.codePointCount(0, reloadedName.length()), "库内应是 128 个码点");

        // 129 码点超限：仍须 422，并且能指回 name 字段
        expectRejected(post("/api/admin/attractions").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + emojiOf(129) + "\",\"city\":\"大理\",\"dataSource\":\"测试\"}"),
                "name", "创建景点（129 码点）");
    }

    // ------------------------------------------------------------------
    // 景点：city ≤ 64、dataSource ≤ 500 的边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建景点：city 65 码点 / dataSource 501 码点分别判 422 且指回对应字段")
    void attractionRejectsEachFieldBeyondItsCodePointLimit() throws Exception {
        expectRejected(post("/api/admin/attractions").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"大理古城\",\"city\":\"" + emojiOf(65) + "\",\"dataSource\":\"测试\"}"),
                "city", "创建景点（city 65 码点）");

        expectRejected(post("/api/admin/attractions").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"大理古城\",\"city\":\"大理\",\"dataSource\":\"" + emojiOf(501) + "\"}"),
                "dataSource", "创建景点（dataSource 501 码点）");
    }

    // ------------------------------------------------------------------
    // 常用出行人：name ≤ 64、emergencyName ≤ 64（TravelerController）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新增出行人：64 码点 / 128 码元的姓名被接受并原样落库，65 码点判 422")
    void travelerAcceptsCodePointBoundedEmojiName() throws Exception {
        String name = emojiOf(64);
        String emergencyName = emojiOf(64);
        assertTrue(name.length() > 64, "前提：码元数已超过契约上限，@Size 会误拒");

        JsonNode created = data(mvc.perform(post("/api/travelers").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(travelerBody(name, emergencyName)))
                .andExpect(status().isCreated())
                .andReturn().getResponse());

        assertEquals(name, created.get("name").asString(), "出行人姓名必须原样返回");
        assertEquals(emergencyName, created.get("emergencyName").asString(), "紧急联系人姓名必须原样返回");

        expectRejected(post("/api/travelers").header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(travelerBody(emojiOf(65), "李四")),
                "name", "新增出行人（65 码点）");
    }

    // ------------------------------------------------------------------
    // 线路：name 2–200、departureCity ≤ 64（AdminRouteController）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建线路：200 码点 / 400 码元的名称被接受，201 码点判 422")
    void routeAcceptsCodePointBoundedEmojiName() throws Exception {
        String name = emojiOf(200);
        assertTrue(name.length() > 200, "前提：码元数已超过契约上限，@Size 会误拒");

        JsonNode created = data(mvc.perform(post("/api/admin/routes").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"departureCity\":\"上海\","
                                + "\"destination\":\"云南\",\"durationDays\":6}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(name, created.get("name").asString(), "线路名称必须原样返回");

        expectRejected(post("/api/admin/routes").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + emojiOf(201) + "\",\"departureCity\":\"上海\","
                        + "\"destination\":\"云南\",\"durationDays\":6}"),
                "name", "创建线路（201 码点）");
    }

    // ------------------------------------------------------------------
    // 在线咨询：title ≤ 100、content ≤ 2000（ConsultationController）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提交咨询：100 码点 / 200 码元的标题被接受，101 码点判 422")
    void consultationAcceptsCodePointBoundedEmojiTitle() throws Exception {
        String title = emojiOf(100);
        assertTrue(title.length() > 100, "前提：码元数已超过契约上限，@Size 会误拒");

        JsonNode created = data(mvc.perform(post("/api/consultations").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"请问退款多久到账？\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(title, created.get("title").asString(), "问题标题必须原样返回");

        expectRejected(post("/api/consultations").header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + emojiOf(101) + "\",\"content\":\"请问退款多久到账？\"}"),
                "title", "提交咨询（101 码点）");
    }

    // ------------------------------------------------------------------
    // 个人资料：nickname ≤ 32（AccountController）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("修改资料：32 码点 / 64 码元的昵称被接受，33 码点判 422")
    void profileAcceptsCodePointBoundedEmojiNickname() throws Exception {
        String nickname = emojiOf(32);
        assertTrue(nickname.length() > 32, "前提：码元数已超过契约上限，@Size 会误拒");

        JsonNode updated = data(mvc.perform(put("/api/account/profile").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse());
        assertEquals(nickname, updated.get("nickname").asString(), "昵称必须原样返回");

        expectRejected(put("/api/account/profile").header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + emojiOf(33) + "\"}"),
                "nickname", "修改资料（33 码点）");
    }

    // ------------------------------------------------------------------
    // 夹具与断言工具
    // ------------------------------------------------------------------

    private String travelerBody(String name, String emergencyName) {
        return "{\"name\":\"" + name + "\",\"gender\":\"MALE\",\"birthDate\":\"1990-01-01\","
                + "\"idType\":\"CHINESE_ID_CARD\",\"idNo\":\"310101199001011234\","
                + "\"emergencyName\":\"" + emergencyName + "\",\"emergencyPhone\":\"13800138000\"}";
    }

    private SysUser account() {
        SysUser user = new SysUser();
        user.username = "cp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.nickname = "码点口径测试";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private String bearer(SysUser user, String role) {
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of(role));
    }

    private JsonNode data(MockHttpServletResponse response) throws Exception {
        JsonNode body = json.readTree(response.getContentAsString());
        assertTrue(body.has("data"), "响应必须是统一信封：" + body);
        return body.get("data");
    }

    /** 断言 422，且 {@code errors[]} 里能指回指定字段（避免"拒绝理由指错字段"也算通过）。 */
    private void expectRejected(MockHttpServletRequestBuilder request, String field, String label)
            throws Exception {
        var response = mvc.perform(request).andExpect(status().isUnprocessableContent())
                .andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString());
        assertEquals("VALIDATION_ERROR", body.get("code").asString(), label + "：错误码应为 VALIDATION_ERROR");
        JsonNode errors = body.get("errors");
        assertNotNull(errors, label + "：422 响应必须带 errors[]：" + body);
        boolean matched = false;
        StringBuilder seen = new StringBuilder();
        for (JsonNode error : errors) {
            String actualField = error.path("field").asString();
            seen.append("\n  - ").append(actualField).append(": ").append(error.path("message").asString());
            if (field.equals(actualField)) {
                matched = true;
            }
        }
        assertTrue(matched, label + "：errors[] 里没有 " + field + " 上的错误，实际为：" + seen);
    }
}
