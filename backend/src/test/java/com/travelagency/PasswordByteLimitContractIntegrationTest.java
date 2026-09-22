package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Staff;
import com.travelagency.domain.entity.SysRole;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.SysUserRole;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.StaffMapper;
import com.travelagency.domain.mapper.SysRoleMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密码字段的 UTF-8 字节上限契约测试（真 MySQL + 真 HTTP 层）。
 *
 * <p><b>缺陷</b>：密码上限被写成了「72 个字符」。契约用 JSON Schema 的
 * {@code maxLength: 72}（单位是<b>字符</b>），BCrypt 的上限却是 72 个 <b>UTF-8 字节</b>，
 * 两者只在纯 ASCII 下等价。25 个汉字只有 25 个字符却占 75 字节 —— 能通过
 * {@code @Size(max = 72)}，随后 {@code BCryptPasswordEncoder#encode} 抛
 * {@code IllegalArgumentException: password cannot be more than 72 bytes}，
 * 被全局兜底成 <b>500</b>（不是契约里的 422）。</p>
 *
 * <p><b>为什么必须走真库 + 真 HTTP</b>：这个缺陷的可见症状就是 HTTP 状态码本身
 * （500 还是 422）以及「失败之后库里有没有多出一行 / password_hash 有没有被动过」，
 * 纯 DTO 单测断言不到。</p>
 *
 * <p><b>断言 errors[] 的方式</b>：不用 {@code jsonPath("$.errors[0]")}。一个字段可能同时
 * 命中 {@code @Size} 与字节上限两条约束（例如 73 个 ASCII），
 * {@code errors[]} 的先后顺序不是契约的一部分，按下标断言会变成脆断言。
 * 这里改为解析响应体后按「字段名 + 文案片段」查找，与顺序无关。</p>
 *
 * <p>本类刻意不继承 {@code AdminAccountsContractIntegrationTest}：JUnit 会把父类的
 * {@code @Test} 一并继承过来再跑一遍。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        // 显式清空支付宝配置，避免本机导出的 ALIPAY_* 环境变量影响测试前提。
        "app.integrations.alipay.gateway-url=",
        "app.integrations.alipay.app-id=",
        "app.integrations.alipay.app-private-key=",
        "app.integrations.alipay.alipay-public-key=",
        "app.integrations.alipay.notify-url="})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class PasswordByteLimitContractIntegrationTest {

    /** 12 字符 / 12 字节，用来做「合法原密码」。 */
    private static final String PASSWORD = "Regression123!";

    /** 24 个汉字：24 字符 / 恰好 72 字节 —— 边界内侧，必须通过。 */
    private static final String CJK_72_BYTES = "汉".repeat(24);
    /** 25 个汉字：25 字符 / 75 字节 —— 评审给出的复现输入，必须 422。 */
    private static final String CJK_75_BYTES = "汉".repeat(25);
    /** 18 个 emoji（每个 4 字节 / 2 个 UTF-16 code unit）：36 字符 / 恰好 72 字节。 */
    private static final String EMOJI_72_BYTES = "\uD83D\uDE00".repeat(18);
    /** 19 个 emoji：38 字符 / 76 字节。 */
    private static final String EMOJI_76_BYTES = "\uD83D\uDE00".repeat(19);
    /** 73 个 ASCII：字符数与字节数同时越界（会同时命中 @Size 与字节上限）。 */
    private static final String ASCII_73 = "a".repeat(73);

    /** 只作为「超限文案」的识别片段，不依赖整句文案（文案改了不该让测试变红）。 */
    private static final String BYTE_LIMIT_MARK = "72 字节";

    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired SysRoleMapper roles;
    @Autowired SysUserRoleMapper userRoles;
    @Autowired StaffMapper staffs;
    @Autowired GuideMapper guides;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;

    private MockMvc mvc;
    private String adminToken;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        SysUser admin = account("ADMIN", null);
        adminToken = "Bearer " + tokens.createToken(admin.id, admin.username, Set.of("ADMIN"));
    }

    /**
     * 夹具自检：先把「字符数没超、字节数超了」这个前提钉死，否则下面的用例会退化成
     * 在测 {@code @Size}（夹具一旦被改成纯 ASCII 就会静默失去意义）。
     */
    @Test
    @DisplayName("夹具自检：字符数与 UTF-8 字节数确实不同，边界值就是 72 字节")
    void fixturesAreActuallyByteBounded() {
        assertEquals(24, CJK_72_BYTES.length());
        assertEquals(72, utf8(CJK_72_BYTES));
        assertEquals(25, CJK_75_BYTES.length());
        assertEquals(75, utf8(CJK_75_BYTES));
        assertEquals(36, EMOJI_72_BYTES.length());
        assertEquals(72, utf8(EMOJI_72_BYTES));
        assertEquals(38, EMOJI_76_BYTES.length());
        assertEquals(76, utf8(EMOJI_76_BYTES));
        assertEquals(73, ASCII_73.length());

        // 关键前提：两个「字符数没超但字节数超了」的样本必须真的没超字符数。
        assertTrue(CJK_75_BYTES.length() <= 72, "25 汉字只有 25 个字符，@Size(max=72) 拦不住它");
        assertTrue(EMOJI_76_BYTES.length() <= 72, "19 个 emoji 只有 38 个字符，@Size(max=72) 拦不住它");

        // 顺便钉住缺陷来源：BCrypt 对 72 字节放行、对 75 字节抛异常。
        assertNotNull(passwords.encode(CJK_72_BYTES));
        assertNotNull(passwords.encode(EMOJI_72_BYTES));
        assertEquals("password cannot be more than 72 bytes",
                assertThrowsIllegalArgument(() -> passwords.encode(CJK_75_BYTES)));
    }

    // ===================== POST /auth/register =====================

    @Test
    @DisplayName("注册：25 个汉字（75 字节）判 422 而不是 500，且不落账号")
    void registerRejectsCjkPasswordBeyondByteLimit() throws Exception {
        assertRegisterRejected(CJK_75_BYTES, 75);
    }

    @Test
    @DisplayName("注册：19 个 emoji（76 字节）判 422，且不落账号")
    void registerRejectsEmojiPasswordBeyondByteLimit() throws Exception {
        assertRegisterRejected(EMOJI_76_BYTES, 76);
    }

    @Test
    @DisplayName("注册：73 个 ASCII 判 422（字符数与字节数同时越界）")
    void registerRejectsTooManyChars() throws Exception {
        assertRegisterRejected(ASCII_73, 73);
    }

    @Test
    @DisplayName("注册：恰好 72 字节（24 汉字 / 18 emoji）通过，且该密码真的能登录")
    void registerAcceptsExactlySeventyTwoBytes() throws Exception {
        for (String boundary : new String[]{CJK_72_BYTES, EMOJI_72_BYTES}) {
            String username = newUsername();
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody(username, boundary)))
                    .andExpect(status().isCreated());

            SysUser created = users.selectOne(new QueryWrapper<SysUser>().eq("username", username));
            assertNotNull(created, "72 字节的密码必须能注册成功：" + utf8(boundary) + " 字节");
            // 只断言「注册没报错」不够：必须证明这个密码真的能通过 BCrypt 往返，
            // 否则「边界可接受」只是校验层放过、加密层未必真的能处理。
            assertTrue(passwords.matches(boundary, created.passwordHash));

            loginExpecting(username, boundary, 200);
            loginExpecting(username, "AnotherPass123!", 401);
        }
    }

    // ===================== PUT /account/password =====================

    @Test
    @DisplayName("改密：新密码 25 汉字判 422，password_hash 不被改动")
    void passwordChangeRejectsOverlongNewPasswordWithoutWriting() throws Exception {
        SysUser me = account(null, PASSWORD);
        String before = users.selectById(me.id).passwordHash;

        JsonNode body = rejected(put("/api/account/password").header("Authorization", bearer(me, "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(passwordBody(PASSWORD, CJK_75_BYTES)));
        assertViolation(body, "newPassword", BYTE_LIMIT_MARK);

        assertEquals(before, users.selectById(me.id).passwordHash, "被拒的改密不得写入 password_hash");
        // 失败不得留下半截状态：原密码必须仍然可用。
        loginExpecting(me.username, PASSWORD, 200);
    }

    @Test
    @DisplayName("改密：原密码 25 汉字判 422（不可能存在的口令），也不得写入")
    void passwordChangeRejectsOverlongCurrentPassword() throws Exception {
        SysUser me = account(null, PASSWORD);
        String before = users.selectById(me.id).passwordHash;

        JsonNode body = rejected(put("/api/account/password").header("Authorization", bearer(me, "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(passwordBody(CJK_75_BYTES, "BrandNew123!")));
        assertViolation(body, "currentPassword", BYTE_LIMIT_MARK);

        assertEquals(before, users.selectById(me.id).passwordHash);
    }

    @Test
    @DisplayName("改密：19 个 emoji 判 422，且密码没有被截断后偷偷生效")
    void passwordChangeDoesNotSilentlyTruncate() throws Exception {
        SysUser me = account(null, PASSWORD);
        String before = users.selectById(me.id).passwordHash;

        JsonNode body = rejected(put("/api/account/password").header("Authorization", bearer(me, "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(passwordBody(PASSWORD, EMOJI_76_BYTES)));
        assertViolation(body, "newPassword", BYTE_LIMIT_MARK);

        assertEquals(before, users.selectById(me.id).passwordHash);
        // 若实现改成「悄悄截断到 72 字节再加密」，密码就会变成 18 个 emoji 并登录成功，这里会变红。
        loginExpecting(me.username, PASSWORD, 200);
        loginExpecting(me.username, EMOJI_72_BYTES, 401);
    }

    // ===================== POST /admin/staff =====================

    @Test
    @DisplayName("建员工：25 汉字判 422，且 sys_user / staff 都不新增行")
    void staffCreateRejectsOverlongPasswordWithoutCreatingRows() throws Exception {
        String username = newUsername();
        String employeeNo = newEmployeeNo();
        long usersBefore = users.selectCount(null);
        long staffBefore = staffs.selectCount(null);

        JsonNode body = rejected(post("/api/admin/staff").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(staffBody(username, CJK_75_BYTES, employeeNo)));
        assertViolation(body, "password", BYTE_LIMIT_MARK);

        assertNull(users.selectOne(new QueryWrapper<SysUser>().eq("username", username)),
                "校验失败不得创建登录账号");
        assertNull(staffs.selectOne(new QueryWrapper<Staff>().eq("employee_no", employeeNo)),
                "校验失败不得创建员工档案");
        assertEquals(usersBefore, users.selectCount(null), "sys_user 行数不得变化");
        assertEquals(staffBefore, staffs.selectCount(null), "staff 行数不得变化");
    }

    // ===================== POST /admin/guides =====================

    @Test
    @DisplayName("建导游：25 汉字判 422，且 sys_user / guide 都不新增行")
    void guideCreateRejectsOverlongPasswordWithoutCreatingRows() throws Exception {
        String username = newUsername();
        long usersBefore = users.selectCount(null);
        long guidesBefore = guides.selectCount(null);

        JsonNode body = rejected(post("/api/admin/guides").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(guideBody(username, CJK_75_BYTES)));
        assertViolation(body, "password", BYTE_LIMIT_MARK);

        assertNull(users.selectOne(new QueryWrapper<SysUser>().eq("username", username)));
        assertEquals(usersBefore, users.selectCount(null), "sys_user 行数不得变化");
        assertEquals(guidesBefore, guides.selectCount(null), "guide 行数不得变化");
    }

    // ===================== POST /auth/login =====================

    @Test
    @DisplayName("登录：超过 72 字节判 422（不是 500），长度合法的错误密码仍判 401")
    void loginRejectsOverlongPasswordWithoutChanging401Semantics() throws Exception {
        SysUser me = account(null, PASSWORD);

        for (String overlong : new String[]{CJK_75_BYTES, EMOJI_76_BYTES, ASCII_73}) {
            JsonNode body = rejected(loginRequest(me.username, overlong));
            assertViolation(body, "password", BYTE_LIMIT_MARK);
        }

        // 回归护栏：加约束不能顺带把「密码错」从 401 改成 422 ——
        // 前端 axios 拦截器把任何 401 当登录态失效，登录接口的失败语义必须保持。
        loginExpecting(me.username, "WrongPass123!", 401);
        loginExpecting(me.username, "short", 401);
        loginExpecting("no_such_user_" + shortId(), PASSWORD, 401);
    }

    // ===================== 夹具与断言工具 =====================

    /** 三类越界口令的公共断言：422 + errors[] 指向 password + 库里没有新账号。 */
    private void assertRegisterRejected(String overlongPassword, int expectedBytes) throws Exception {
        assertEquals(expectedBytes, utf8(overlongPassword), "夹具的字节数必须与用例名一致");
        assertTrue(expectedBytes > 72, "越界样本的 UTF-8 字节数必须大于 72");

        String username = newUsername();
        long before = users.selectCount(null);

        JsonNode body = rejected(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(username, overlongPassword)));
        assertViolation(body, "password", BYTE_LIMIT_MARK);

        assertNull(users.selectOne(new QueryWrapper<SysUser>().eq("username", username)),
                "校验失败不得创建账号");
        assertEquals(before, users.selectCount(null), "sys_user 行数不得变化");
    }

    /** 断言 422 + {@code VALIDATION_ERROR} 并返回响应体，供后续按字段名查 errors[]。 */
    private JsonNode rejected(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request)
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse();
        // 显式断言状态码：500 说明非法口令仍被放进了加密层，是本类要防的主要回归。
        assertEquals(422, response.getStatus(), "超长口令必须是 422，不能是 500");
        JsonNode body = json.readTree(bodyText(response));
        assertEquals("VALIDATION_ERROR", body.get("code").asString());
        return body;
    }

    /** 在 errors[] 里找「字段名 + 文案片段」都命中的条目，与 errors[] 的先后顺序无关。 */
    private static void assertViolation(JsonNode body, String field, String messageFragment) {
        JsonNode errors = body.get("errors");
        assertNotNull(errors, "422 响应必须带 errors[]：" + body);
        StringBuilder seen = new StringBuilder();
        for (JsonNode error : errors) {
            String actualField = error.get("field").asString();
            String message = error.get("message").asString();
            seen.append("\n  - ").append(actualField).append(": ").append(message);
            if (field.equals(actualField) && message.contains(messageFragment)) {
                return;
            }
        }
        throw new AssertionError("errors[] 里没有 " + field + " 上的「" + messageFragment
                + "」错误，实际为：" + seen);
    }

    private SysUser account(String roleCode, String rawPassword) {
        SysUser user = new SysUser();
        user.username = newUsername();
        user.nickname = "字节上限回归";
        user.realName = "字节上限回归";
        user.passwordHash = rawPassword == null ? "unused-test-hash" : passwords.encode(rawPassword);
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        if (roleCode != null) {
            SysRole role = roles.selectOne(new QueryWrapper<SysRole>().eq("code", roleCode));
            SysUserRole link = new SysUserRole();
            link.userId = user.id;
            link.roleId = role.id;
            userRoles.insert(link);
        }
        return user;
    }

    private String bearer(SysUser user, String role) {
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of(role));
    }

    private static MockHttpServletRequestBuilder loginRequest(String username, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private void loginExpecting(String username, String password, int status) throws Exception {
        mvc.perform(loginRequest(username, password)).andExpect(status().is(status));
    }

    private static String registerBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"nickname\":\"字节上限回归\"}";
    }

    private static String passwordBody(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    private static String staffBody(String username, String password, String employeeNo) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"realName\":\"王顾问\","
                + "\"phone\":\"13800138002\",\"employeeNo\":\"" + employeeNo + "\",\"department\":\"运营部\","
                + "\"position\":\"行程顾问\"}";
    }

    private static String guideBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"name\":\"李导\","
                + "\"phone\":\"13800138001\",\"intro\":\"字节上限回归\"}";
    }

    private static int utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 单独抽出，避免测试里出现 try/catch 噪声；返回异常消息便于断言。 */
    private static String assertThrowsIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
        throw new AssertionError("期望 BCrypt 因为超过 72 字节而抛 IllegalArgumentException，但没有抛");
    }

    /** MockHttpServletResponse 默认按 ISO-8859-1 解码，中文会乱码，必须按 UTF-8 读字节。 */
    private static String bodyText(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 满足契约 username pattern ^[A-Za-z0-9_]{3,32}$。 */
    private static String newUsername() {
        return "pw_" + shortId();
    }

    private static String newEmployeeNo() {
        return "EMP" + shortId();
    }
}
