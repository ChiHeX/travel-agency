package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「改密码只能由本人发起」这条产品规则的契约边界。
 *
 * <p>#46（后台导游管理）的收尾记录把「导游密码重置」列为范围外：契约没有对应端点，改密走导游本人的
 * 账号安全流程（{@code PUT /account/password}）。结合 PRD §38（导游维护项只有姓名 / 联系方式 /
 * 简介 / 状态）与 §43（「不得由管理员任意修改用户密码」），这条的正确收口是<b>明确不做</b>，
 * 而不是补一个管理员重置密码的端点 —— 那样会反过来推翻已有的产品规则。</p>
 *
 * <p>问题在于「不存在的能力」既没有编译期约束，也没有契约期约束：谁往 {@code GuideUpdateRequest} /
 * {@code StaffUpdateRequest} 里加一个 {@code password} 字段，或者新加一个
 * {@code /admin/.../password} 端点，现有测试一条都不会红。因此本类把这条边界显式钉下来：</p>
 * <ul>
 *   <li>在资料维护端点提交 {@code password} → 400（契约外字段），且整个请求不产生任何写入
 *       —— 密码哈希原样、姓名也没被「改一半」，原密码仍能登录、新密码登录 401；</li>
 *   <li>{@code /admin/guides/{id}/password}、{@code /admin/staff/{id}/password}、
 *       {@code /admin/users/{id}/password} 一律 404（用真实存在的 id 探测，
 *       避免把「路径不存在」与「资源不存在」混为一谈）；</li>
 *   <li>{@code PUT /account/password} 只作用于当前登录账号：改密后自己的旧密码失效、新密码生效，
 *       其它账号的哈希与登录完全不受影响（该端点没有「目标账号」参数，提交一个也会被判 400）。</li>
 * </ul>
 *
 * <p>必须走真库 + 真 HTTP：400 来自 Jackson 的严格模式（{@code FAIL_ON_UNKNOWN_PROPERTIES}），
 * 404 来自映射表本身，而「没有写入」只能靠回查 {@code sys_user.password_hash} 与真实登录验证。</p>
 *
 * <p><b>需要数据库</b>（未配置时整个类被跳过）：</p>
 * <pre>
 * $env:TRAVEL_MYSQL_TEST = "true"
 * mvn -ntp test -Dtest=PasswordChangeBoundaryContractIntegrationTest
 * </pre>
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class PasswordChangeBoundaryContractIntegrationTest {

    private static final String INITIAL_PASSWORD = "DemoPass123!";
    private static final String NEW_PASSWORD = "Changed456!";

    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwordEncoder;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * 导游资料维护端点不接受密码：多传的字段被严格模式判 400，整请求不产生写入。
     *
     * <p>「整请求不产生写入」是重点：如果实现先改姓名再校验字段，页面会看到「保存失败但名字变了」，
     * 而密码是否被改掉更难从界面上发现。</p>
     */
    @Test
    @DisplayName("PUT /admin/guides/{guideId} 带 password → 400，且姓名与密码都不改动")
    void guideProfileUpdateRejectsPasswordField() throws Exception {
        JsonNode guide = createGuide();
        long guideId = Long.parseLong(guide.path("id").asString());
        long userId = Long.parseLong(guide.path("userId").asString());
        String hashBefore = users.selectById(userId).passwordHash;

        mvc().perform(put("/api/admin/guides/" + guideId).header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"李导（改名）\",\"phone\":\"13800138001\",\"intro\":\"试图顺带改密码\","
                                + "\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertEquals(hashBefore, users.selectById(userId).passwordHash, "密码哈希必须原样");
        assertEquals("李导", guideName(guideId), "整个请求都该被拒绝，姓名不能被改一半");
        loginSucceeds(guide.path("username").asString(), INITIAL_PASSWORD);
        loginFails(guide.path("username").asString(), NEW_PASSWORD);
    }

    @Test
    @DisplayName("PUT /admin/staff/{staffId} 带 password → 400，且资料与密码都不改动")
    void staffProfileUpdateRejectsPasswordField() throws Exception {
        String token = adminToken();
        String username = "staff_" + shortId();
        JsonNode staff = data(mvc()
                .perform(post("/api/admin/staff").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + INITIAL_PASSWORD + "\","
                                + "\"realName\":\"王顾问\",\"employeeNo\":\"EMP" + shortId() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        long staffId = Long.parseLong(staff.path("id").asString());
        long userId = Long.parseLong(staff.path("userId").asString());
        String hashBefore = users.selectById(userId).passwordHash;

        mvc().perform(put("/api/admin/staff/" + staffId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"王顾问（改名）\",\"employeeNo\":\"EMP" + shortId() + "\","
                                + "\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertEquals(hashBefore, users.selectById(userId).passwordHash, "密码哈希必须原样");
        assertEquals("王顾问", users.selectById(userId).realName, "整个请求都该被拒绝，姓名不能被改一半");
        loginSucceeds(username, INITIAL_PASSWORD);
        loginFails(username, NEW_PASSWORD);
    }

    /**
     * 管理端没有「重置他人密码」的入口：这些路径在映射表里就不存在。
     *
     * <p>刻意用<b>真实存在的</b> id 探测，否则「路径不存在」与「资源不存在」都是 404，用例会失去意义。</p>
     */
    @Test
    @DisplayName("管理端不存在改密端点：/admin/{guides,staff,users}/{id}/password 一律 404")
    void adminPasswordResetEndpointsDoNotExist() throws Exception {
        JsonNode guide = createGuide();
        long guideId = Long.parseLong(guide.path("id").asString());
        long userId = Long.parseLong(guide.path("userId").asString());
        String hashBefore = users.selectById(userId).passwordHash;
        String token = adminToken();
        String body = "{\"password\":\"" + NEW_PASSWORD + "\"}";

        String[] paths = {
                "/api/admin/guides/" + guideId + "/password",
                "/api/admin/staff/" + guideId + "/password",
                "/api/admin/users/" + userId + "/password"
        };
        for (String path : paths) {
            mvc().perform(put(path).header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
            mvc().perform(post(path).header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
            mvc().perform(patch(path).header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        }

        assertEquals(hashBefore, users.selectById(userId).passwordHash, "探测之后密码哈希必须原样");
        loginSucceeds(guide.path("username").asString(), INITIAL_PASSWORD);
        loginFails(guide.path("username").asString(), NEW_PASSWORD);
    }

    /**
     * {@code PUT /account/password} 只作用于当前登录账号：它没有「目标账号」参数，
     * 提交一个也会因为契约外字段被判 400（严格模式），而不是被悄悄忽略。
     */
    @Test
    @DisplayName("改密只改当前账号：他自己的旧密码失效、新密码生效，别人不受影响")
    void passwordChangeOnlyAffectsTheCurrentAccount() throws Exception {
        JsonNode guide = createGuide();
        long userId = Long.parseLong(guide.path("userId").asString());
        String username = guide.path("username").asString();
        SysUser admin = account("pwd_admin");
        String hashBefore = users.selectById(userId).passwordHash;
        String adminHashBefore = admin.passwordHash;
        String guideToken = "Bearer " + tokens.createToken(userId, username, Set.of("GUIDE"));

        // 契约外字段：不许通过该端点指定别人的账号
        mvc().perform(put("/api/account/password").header("Authorization", guideToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + INITIAL_PASSWORD + "\",\"newPassword\":\""
                                + NEW_PASSWORD + "\",\"targetUserId\":\"" + admin.id + "\"}"))
                .andExpect(status().isBadRequest());

        mvc().perform(put("/api/account/password").header("Authorization", guideToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + INITIAL_PASSWORD + "\",\"newPassword\":\""
                                + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertNotEquals(hashBefore, users.selectById(userId).passwordHash, "本人的密码必须真的换掉");
        assertEquals(adminHashBefore, users.selectById(admin.id).passwordHash,
                "另一个人（这里是管理员）的密码不受影响");
        loginFails(username, INITIAL_PASSWORD);
        loginSucceeds(username, NEW_PASSWORD);
    }

    /** 导游的读接口不得出现密码或哈希（PRD §53.4：密码不得返回前端）。 */
    @Test
    @DisplayName("导游读接口不返回密码或其哈希")
    void guideReadEndpointsDoNotExposePassword() throws Exception {
        String token = adminToken();
        JsonNode guide = createGuide();
        long guideId = Long.parseLong(guide.path("id").asString());

        JsonNode detail = data(mvc()
                .perform(get("/api/admin/guides/" + guideId).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse());
        assertNull(detail.get("password"), "详情不得包含 password 字段");
        assertNull(detail.get("passwordHash"), "详情不得包含 passwordHash 字段");

        JsonNode page = data(mvc()
                .perform(get("/api/admin/guides").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse());
        assertTrue(page.path("items").isArray());
        for (JsonNode item : page.path("items")) {
            assertNull(item.get("password"));
            assertNull(item.get("passwordHash"));
        }
    }

    /**
     * 旧口令的<b>自助升级路径</b>：历史口令是按 UTF-16 码元口径创建并保存的（当时的校验是
     * {@code @Size(min = 8)}，一个 emoji 记 2 个码元），「😀😀😀😀」只有 4 个字符（码点）
     * 却有 8 个码元 —— 当时能注册、现在也仍能登录。
     *
     * <p>改密接口若在核对原密码之前就以「不足 8 个字符」回 422，这些账号会既改不了密码、
     * 又没有管理员重置入口（本类其余用例钉住的正是这一点），等于被永久锁死在旧口令上。
     * 因此<b>验证类字段（原密码 / 登录口令）只做哈希匹配，不套用新密码的设置规则</b>；
     * 新密码仍严格要求 8 个码点。</p>
     */
    @Test
    @DisplayName("旧口令（4 个码点 / 8 个码元）能登录，也能自助升级为合规新口令")
    void legacyShortPasswordCanStillBeChanged() throws Exception {
        String legacy = "😀😀😀😀";
        assertEquals(4, legacy.codePointCount(0, legacy.length()), "前提：码点数不足 8");
        assertEquals(8, legacy.length(), "前提：UTF-16 码元数恰好等于旧规则的下限");
        assertTrue(legacy.getBytes(StandardCharsets.UTF_8).length <= 72, "前提：字节数在上限之内");

        SysUser user = accountWithPassword("legacy_pwd", legacy);
        String username = user.username;

        // 1) 旧口令仍能登录：登录字段不设长度下限，只做哈希匹配
        loginSucceeds(username, legacy);

        String token = "Bearer " + tokens.createToken(user.id, username, Set.of("USER"));
        String hashBefore = users.selectById(user.id).passwordHash;

        // 2) 新密码仍套用设置规则：3 个码点的新密码被 422 拒绝，且不产生写入
        mvc().perform(put("/api/account/password").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + legacy + "\",\"newPassword\":\"😀😀😀\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        assertEquals(hashBefore, users.selectById(user.id).passwordHash, "校验失败不得改库");

        // 3) 用旧口令自助升级：204，之后旧口令失效、新口令生效
        mvc().perform(put("/api/account/password").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + legacy + "\",\"newPassword\":\""
                                + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        loginFails(username, legacy);
        loginSucceeds(username, NEW_PASSWORD);
    }

    /**
     * 原密码只保留两个守卫：非空 + UTF-8 不超过 72 字节（超过 72 字节的输入不可能是任何已存口令，
     * 且会让 BCrypt 抛异常变成 500）。长度下限不做 ——
     * 不匹配由哈希比对给出 422「原密码不正确」，而不是「长度应为 8-72 位」。
     */
    @Test
    @DisplayName("原密码只校验非空与字节上限，长度下限交给哈希匹配")
    void currentPasswordHasNoLengthRuleOnlyByteCap() throws Exception {
        SysUser user = accountWithPassword("pwd_probe", NEW_PASSWORD);
        String token = "Bearer " + tokens.createToken(user.id, user.username, Set.of("USER"));
        String hashBefore = users.selectById(user.id).passwordHash;

        // 2 个码点（8 字节）的原密码：不因长度被拒，而是走到哈希比对后回「原密码不正确」
        mvc().perform(put("/api/account/password").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"😀😀\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("原密码不正确"));

        // 73 个字节：超出 BCrypt 硬上限，按 422 拒绝（不是 500），错误指回该字段
        mvc().perform(put("/api/account/password").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + "a".repeat(73) + "\",\"newPassword\":\""
                                + NEW_PASSWORD + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"));
        assertEquals(hashBefore, users.selectById(user.id).passwordHash, "校验失败不得改库");
    }

    // ===================== 夹具与断言工具 =====================

    /** 落库一个带真实 BCrypt 哈希的账号：模拟「改动前就已存在」的口令。 */
    private SysUser accountWithPassword(String prefix, String rawPassword) {
        SysUser user = new SysUser();
        user.username = prefix + "_" + shortId();
        user.nickname = user.username;
        user.realName = user.username;
        user.passwordHash = passwordEncoder.encode(rawPassword);
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    /** 通过契约端点建档导游：账号 + 初始密码 + 资料一次写入。 */
    private JsonNode createGuide() throws Exception {
        String username = "guide_" + shortId();
        return data(mvc()
                .perform(post("/api/admin/guides").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + INITIAL_PASSWORD + "\","
                                + "\"name\":\"李导\",\"phone\":\"13800138001\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
    }

    private String guideName(long guideId) throws Exception {
        return data(mvc()
                .perform(get("/api/admin/guides/" + guideId).header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse()).path("name").asString();
    }

    private void loginSucceeds(String username, String password) throws Exception {
        mvc().perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }

    private void loginFails(String username, String password) throws Exception {
        mvc().perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** JwtAuthenticationFilter 会回查账号状态，所以令牌对应的账号必须真实落库。 */
    private SysUser account(String prefix) {
        SysUser user = new SysUser();
        user.username = prefix + "_" + shortId();
        user.nickname = user.username;
        user.realName = user.username;
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private String adminToken() {
        SysUser user = account("pwd_admin");
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of("ADMIN"));
    }

    /** MockHttpServletResponse 默认按 ISO-8859-1 解码，中文会乱码，必须按 UTF-8 读字节。 */
    private JsonNode data(MockHttpServletResponse response) {
        return json.readTree(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8)).get("data");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
