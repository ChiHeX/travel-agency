package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Staff;
import com.travelagency.domain.entity.SysRole;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.SysUserRole;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账户模块契约集成测试：Account + Admin Accounts 两组端点，走真 MySQL + 真实 HTTP 层。
 *
 * <p><b>起因</b>：把 {@code docs/openapi.yaml} 的契约端点、后端 {@code @*Mapping} 与
 * 前端 {@code api/modules.js} 三方做机械对账后，全库只剩 4 个「契约里有、后端没有」的路径，
 * 而这 4 个在前端都已有调用封装：
 * <ul>
 *   <li>{@code PUT /account/password} —— SecurityView「账号安全」页点保存即 404；</li>
 *   <li>{@code GET /admin/users/{userId}} —— {@code adminApi.user(userId)} 404；</li>
 *   <li>{@code PUT /admin/staff/{staffId}} —— {@code adminApi.updateStaff} 404；</li>
 *   <li>{@code PATCH /admin/staff/{staffId}/status} —— {@code adminApi.updateStaffStatus} 404。</li>
 * </ul>
 *
 * <p>补齐过程中又发现同组端点的<b>请求/响应形状与契约矛盾</b>（不是缺路径，是答错内容），
 * 本类一并钉住，每条都对应一个可复现的线上后果：
 * <ul>
 *   <li>{@code GET /admin/users} 直出 {@code AdminUserView}：{@code status} 是整数 1/0、
 *       缺契约必填的 {@code roles}、头像字段名不是 {@code avatarUrl}。前端
 *       {@code AdminUsersView} 用 {@code row.status === 'ACTIVE'} 判断，整数永远不相等，
 *       于是<b>所有账号都被渲染成「已冻结」</b>。</li>
 *   <li>{@code PATCH /admin/users/{id}/status} 接自由文本并 {@code Integer.parseInt}：
 *       契约和前端传的都是 ACTIVE/DISABLED，一调即 <b>500</b>；响应体也不是 UserEnvelope。</li>
 *   <li>{@code GET /admin/staff} / {@code POST /admin/staff} 直出 {@code Staff} 实体：
 *       缺 {@code username}/{@code realName}/{@code status}（都在 sys_user 上），
 *       并违反「Controller 不得直接暴露 Entity」。</li>
 *   <li>{@code POST /admin/staff} 的请求体缺 {@code employeeNo}：契约里冻结的请求体
 *       因为多出这个字段被全局 {@code FAIL_ON_UNKNOWN_PROPERTIES} 判 400，<b>根本发不进来</b>。</li>
 * </ul>
 *
 * <p><b>为什么必须走真库 + 真 HTTP</b>：这些缺陷全部位于「Controller → Jackson 序列化 → 信封」
 * 这一段，纯 Mockito 单测直接断言 service 返回值，编译也不报错，只有真实响应才能暴露。</p>
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
class AdminAccountsContractIntegrationTest {

    private static final String PASSWORD = "Regression123!";
    private static final String NEW_PASSWORD = "Regression456!";
    private static final String ADMIN = "ADMIN";
    private static final String STAFF = "STAFF";

    /** 契约 User 的全部字段（additionalProperties: false）。 */
    private static final Set<String> USER_FIELDS = Set.of(
            "id", "username", "nickname", "realName", "phone", "email", "avatarUrl", "roles", "status", "createdAt");
    /** 契约 User 的 required。 */
    private static final Set<String> USER_REQUIRED = Set.of(
            "id", "username", "nickname", "roles", "status", "createdAt");
    /** 契约 Staff 的全部字段（additionalProperties: false）。 */
    private static final Set<String> STAFF_FIELDS = Set.of(
            "id", "userId", "username", "realName", "phone", "employeeNo",
            "department", "position", "status", "createdAt", "updatedAt");
    /** 契约 Staff 的 required。 */
    private static final Set<String> STAFF_REQUIRED = Set.of(
            "id", "userId", "username", "realName", "employeeNo", "status", "createdAt", "updatedAt");

    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired SysRoleMapper roles;
    @Autowired SysUserRoleMapper userRoles;
    @Autowired StaffMapper staffs;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;

    private MockMvc mvc;
    private SysUser admin;
    private SysUser staffAccount;
    private String adminToken;
    private String staffToken;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = account(ADMIN, null);
        staffAccount = account(STAFF, null);
        adminToken = bearer(admin, ADMIN);
        staffToken = bearer(staffAccount, STAFF);
    }

    // ===================== PUT /account/password =====================

    @Test
    @DisplayName("PUT /account/password：204 无响应体，新密码生效、旧密码失效")
    void passwordChangeRotatesCredentialAndReturnsNoContent() throws Exception {
        SysUser me = account(null, PASSWORD);

        mvc.perform(put("/api/account/password").header("Authorization", bearer(me, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent())
                // 契约的成功响应没有响应体，不能顺手套 ApiResponse 信封
                .andExpect(content().string(""));

        String hash = users.selectById(me.id).passwordHash;
        assertTrue(passwords.matches(NEW_PASSWORD, hash), "新密码必须生效");
        assertFalse(passwords.matches(PASSWORD, hash), "旧密码必须失效");

        login(me.username, NEW_PASSWORD).andExpect(status().isOk());
        login(me.username, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /account/password：原密码错误判 422 且不改动任何数据")
    void passwordChangeRejectsWrongCurrentPassword() throws Exception {
        SysUser me = account(null, PASSWORD);
        String token = bearer(me, "USER");
        String before = users.selectById(me.id).passwordHash;

        var response = mvc.perform(put("/api/account/password").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("WrongOld123!", NEW_PASSWORD)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn().getResponse();

        // 刻意不是 401：前端 axios 把任何 401 当登录态失效并强制登出
        // （frontend/src/api/request.js 派发 travel-auth-expired），
        // 打错一次原密码就被踢出登录，且看不到错误提示。
        assertEquals(422, response.getStatus());
        assertEquals(before, users.selectById(me.id).passwordHash, "失败的改密不得产生任何写入");
        login(me.username, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /account/password：新旧相同判 422")
    void passwordChangeRejectsSamePassword() throws Exception {
        SysUser me = account(null, PASSWORD);
        String before = users.selectById(me.id).passwordHash;

        mvc.perform(put("/api/account/password").header("Authorization", bearer(me, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(PASSWORD, PASSWORD)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertEquals(before, users.selectById(me.id).passwordHash);
    }

    @Test
    @DisplayName("PUT /account/password：字段语义校验与契约一致（8-72 位、契约外字段 400）")
    void passwordChangeEnforcesFrozenContract() throws Exception {
        SysUser me = account(null, PASSWORD);
        String token = bearer(me, "USER");
        String before = users.selectById(me.id).passwordHash;

        // 长度不足 → 422 + errors[] 指向具体字段（契约 minLength 8）
        mvc.perform(put("/api/account/password").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(PASSWORD, "short")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));

        // 缺必填字段 → 422
        mvc.perform(put("/api/account/password").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnprocessableContent());

        // 契约 additionalProperties: false：契约外字段必须被拒（全局 FAIL_ON_UNKNOWN_PROPERTIES）
        mvc.perform(put("/api/account/password").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\""
                                + NEW_PASSWORD + "\",\"confirmation\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(before, users.selectById(me.id).passwordHash, "三次被拒的请求都不得写入");
    }

    @Test
    @DisplayName("PUT /account/password：未登录 401")
    void passwordChangeRequiresAuthentication() throws Exception {
        mvc.perform(put("/api/account/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    // ===================== GET /admin/users/{userId} =====================

    @Test
    @DisplayName("GET /admin/users/{userId}：200，字段与契约 User 逐项一致")
    void adminUserDetailMatchesContractShape() throws Exception {
        SysUser target = account("USER", null);
        target.realName = "张行迹";
        target.avatar = "https://example.com/avatar.png";
        users.updateById(target);

        JsonNode data = okData(get("/api/admin/users/" + target.id).header("Authorization", adminToken));
        assertFieldsExactly(data, USER_FIELDS, USER_REQUIRED, "GET /admin/users/{userId} 的 data");
        assertEquals(target.username, data.get("username").asString());
        assertEquals("张行迹", data.get("realName").asString());
        // 契约 AccountStatus 是枚举字符串，不是数据库里的 1/0
        assertEquals("ACTIVE", data.get("status").asString());
        // 头像字段名是 avatarUrl（实体里叫 avatar）
        assertEquals(target.avatar, data.get("avatarUrl").asString());
        assertFalse(data.has("avatar"), "契约 additionalProperties: false，不得出现 avatar");
        assertEquals(Set.of("USER"), names(data.get("roles")));
        assertEquals(target.id.toString(), data.get("id").asString(), "Long 主键必须按字符串输出");
    }

    @Test
    @DisplayName("GET /admin/users/{userId}：不存在或已软删 → 404")
    void adminUserDetailReturnsNotFound() throws Exception {
        mvc.perform(get("/api/admin/users/9223372036854775807").header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        SysUser deleted = account(null, null);
        deleted.deleted = 1;
        users.updateById(deleted);
        mvc.perform(get("/api/admin/users/" + deleted.id).header("Authorization", adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /admin/users/{userId}：STAFF 越权 → 403")
    void adminUserDetailRequiresAdmin() throws Exception {
        mvc.perform(get("/api/admin/users/" + admin.id).header("Authorization", staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ===================== GET /admin/users =====================

    @Test
    @DisplayName("GET /admin/users：status 必须是契约枚举字符串（前端据此渲染「正常使用/已冻结」）")
    void adminUserListSerializesAccountStatusAsEnum() throws Exception {
        SysUser active = account("USER", null);
        SysUser disabled = users.selectById(account("USER", null).id);
        disabled.status = 0;
        users.updateById(disabled);

        JsonNode items = okData(get("/api/admin/users?page=1&size=100").header("Authorization", adminToken))
                .get("items");
        JsonNode activeRow = find(items, active.id.toString());
        JsonNode disabledRow = find(items, disabled.id.toString());
        assertFieldsExactly(activeRow, USER_FIELDS, USER_REQUIRED, "GET /admin/users 列表项");
        assertEquals("ACTIVE", activeRow.get("status").asString());
        assertEquals("DISABLED", disabledRow.get("status").asString());
        assertTrue(activeRow.get("roles").isArray() && activeRow.get("roles").size() > 0,
                "roles 是契约必填，至少要回落到 USER");
    }

    // ===================== PATCH /admin/users/{id}/status =====================

    @Test
    @DisplayName("PATCH /admin/users/{id}/status：接受 ACTIVE/DISABLED 并回 UserEnvelope")
    void adminUserStatusAcceptsContractEnum() throws Exception {
        SysUser target = account("USER", PASSWORD);
        String victimToken = bearer(target, "USER");

        JsonNode disabled = okData(patch("/api/admin/users/" + target.id + "/status")
                .header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"));
        assertFieldsExactly(disabled, USER_FIELDS, USER_REQUIRED, "停用后的 data");
        assertEquals("DISABLED", disabled.get("status").asString());
        assertEquals(0, users.selectById(target.id).status);
        // 停用必须真的生效：旧令牌立刻失效、不能再登录
        mvc.perform(get("/api/auth/me").header("Authorization", victimToken)).andExpect(status().isUnauthorized());
        login(target.username, PASSWORD).andExpect(status().isUnauthorized());

        JsonNode enabled = okData(patch("/api/admin/users/" + target.id + "/status")
                .header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"));
        assertEquals("ACTIVE", enabled.get("status").asString());
        assertEquals(1, users.selectById(target.id).status);
        login(target.username, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /admin/users/{id}/status：非法枚举 422 且零写入；目标不存在 404")
    void adminUserStatusRejectsInvalidInput() throws Exception {
        SysUser target = account("USER", null);

        // 旧实现接的是自由文本 + Integer.parseInt("ACTIVE") → 500；
        // 现在枚举值由 DTO 约束挡在 422，且 "1"/"0" 这类数字不再被接受。
        mvc.perform(patch("/api/admin/users/" + target.id + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"1\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(patch("/api/admin/users/" + target.id + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());
        assertEquals(1, users.selectById(target.id).status, "被拒的请求不得改状态");

        mvc.perform(patch("/api/admin/users/9223372036854775807/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /admin/users/{id}/status：STAFF 越权 → 403 且零写入")
    void adminUserStatusRequiresAdmin() throws Exception {
        SysUser target = account("USER", null);
        mvc.perform(patch("/api/admin/users/" + target.id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
        assertEquals(1, users.selectById(target.id).status);
    }

    // ===================== GET / POST /admin/staff =====================

    @Test
    @DisplayName("GET /admin/staff：返回契约 Staff（username/realName/status 取自 sys_user）")
    void staffListMatchesContractShape() throws Exception {
        SysUser owner = account(STAFF, null);
        Staff staff = staff(owner, newEmployeeNo());

        JsonNode items = okData(get("/api/admin/staff?page=1&size=100").header("Authorization", adminToken))
                .get("items");
        JsonNode row = find(items, staff.id.toString());
        assertFieldsExactly(row, STAFF_FIELDS, STAFF_REQUIRED, "GET /admin/staff 列表项");
        assertEquals(owner.username, row.get("username").asString());
        assertEquals(owner.realName, row.get("realName").asString());
        assertEquals(staff.employeeNo, row.get("employeeNo").asString());
        assertEquals("ACTIVE", row.get("status").asString());
        assertEquals(owner.id.toString(), row.get("userId").asString());
    }

    @Test
    @DisplayName("POST /admin/staff：201 + Location，请求体按契约含 employeeNo")
    void staffCreationUsesContractRequestAndReturnsCreated() throws Exception {
        String username = newUsername();
        String employeeNo = newEmployeeNo();

        var response = mvc.perform(post("/api/admin/staff").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(staffCreateBody(username, employeeNo)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse();

        JsonNode data = json.readTree(body(response)).get("data");
        assertFieldsExactly(data, STAFF_FIELDS, STAFF_REQUIRED, "POST /admin/staff 的 data");
        assertEquals(username, data.get("username").asString());
        assertEquals(employeeNo, data.get("employeeNo").asString(), "工号必须用调用方给的值，不再自动生成");
        assertEquals("ACTIVE", data.get("status").asString());
        assertEquals("/api/admin/staff/" + data.get("id").asString(), response.getHeader("Location"));

        // 账号与档案都要真的落库，且能直接用于登录
        SysUser created = users.selectOne(new QueryWrapper<SysUser>().eq("username", username));
        assertEquals(employeeNo, staffs.selectById(Long.valueOf(data.get("id").asString())).employeeNo);
        assertEquals(Set.of(STAFF), roleCodesOf(created.id), "新账号必须带 STAFF 角色");
        login(username, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.roles[0]").value(STAFF));
    }

    @Test
    @DisplayName("POST /admin/staff：工号撞唯一键 → 409，且在插账号之前就失败")
    void staffCreationRejectsDuplicateEmployeeNo() throws Exception {
        String employeeNo = newEmployeeNo();
        staff(account(STAFF, null), employeeNo);
        long accountsBefore = users.selectCount(null);

        mvc.perform(post("/api/admin/staff").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffCreateBody(newUsername(), employeeNo)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"));

        assertEquals(accountsBefore, users.selectCount(null), "工号冲突必须在创建账号之前失败，不留孤儿账号");
    }

    @Test
    @DisplayName("POST /admin/staff：缺 employeeNo / 用户名不合规 / 注入状态 → 422、422、400，零写入")
    void staffCreationEnforcesFrozenContract() throws Exception {
        long accountsBefore = users.selectCount(null);
        long staffBefore = staffs.selectCount(null);

        // 少了契约 required 的 employeeNo
        mvc.perform(post("/api/admin/staff").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"gap_nobody\",\"password\":\"" + PASSWORD
                                + "\",\"realName\":\"无工号\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("employeeNo"));

        // 用户名不满足契约 pattern ^[A-Za-z0-9_]{3,32}$
        mvc.perform(post("/api/admin/staff").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffCreateBody("ab", newEmployeeNo())))
                .andExpect(status().isUnprocessableContent());

        // 契约外字段 → 400
        mvc.perform(post("/api/admin/staff").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(injectStatus(staffCreateBody(newUsername(), newEmployeeNo()))))
                .andExpect(status().isBadRequest());

        assertEquals(accountsBefore, users.selectCount(null));
        assertEquals(staffBefore, staffs.selectCount(null));
    }

    @Test
    @DisplayName("POST /admin/staff：STAFF 越权 → 403")
    void staffCreationRequiresAdmin() throws Exception {
        mvc.perform(post("/api/admin/staff").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffCreateBody(newUsername(), newEmployeeNo())))
                .andExpect(status().isForbidden());
    }

    // ===================== PUT /admin/staff/{staffId} =====================

    @Test
    @DisplayName("PUT /admin/staff/{staffId}：两个表一起写，返回契约 Staff")
    void staffUpdateWritesBothTables() throws Exception {
        SysUser owner = account(STAFF, null);
        Staff staff = staff(owner, newEmployeeNo());
        String updatedNo = newEmployeeNo();

        JsonNode data = okData(put("/api/admin/staff/" + staff.id).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"realName\":\"王顾问\",\"phone\":\"13800138002\",\"employeeNo\":\"" + updatedNo
                        + "\",\"department\":\"运营部\",\"position\":\"行程顾问\"}"));

        assertFieldsExactly(data, STAFF_FIELDS, STAFF_REQUIRED, "PUT /admin/staff/{staffId} 的 data");
        assertEquals("王顾问", data.get("realName").asString());
        assertEquals(updatedNo, data.get("employeeNo").asString());
        assertEquals("运营部", data.get("department").asString());
        assertEquals("行程顾问", data.get("position").asString());
        assertEquals(owner.username, data.get("username").asString(), "账号名来自 sys_user，不应被改写");

        SysUser savedAccount = users.selectById(owner.id);
        assertEquals("王顾问", savedAccount.realName);
        assertEquals("13800138002", savedAccount.phone);
        Staff saved = staffs.selectById(staff.id);
        assertEquals(updatedNo, saved.employeeNo);
        assertEquals("运营部", saved.department);
        assertEquals(owner.id, saved.userId, "资料修改不得改绑账号");
    }

    @Test
    @DisplayName("PUT /admin/staff/{staffId}：用户自选的昵称不被覆盖")
    void staffUpdateKeepsCustomNickname() throws Exception {
        SysUser owner = account(STAFF, null);
        Staff staff = staff(owner, newEmployeeNo());
        SysUser edited = users.selectById(owner.id);
        edited.nickname = "自选昵称";
        users.updateById(edited);

        okData(put("/api/admin/staff/" + staff.id).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"realName\":\"李工\",\"employeeNo\":\"" + newEmployeeNo() + "\"}"));

        SysUser saved = users.selectById(owner.id);
        assertEquals("李工", saved.realName);
        assertEquals("自选昵称", saved.nickname, "昵称已被用户改过，不应跟着姓名走");
    }

    @Test
    @DisplayName("PUT /admin/staff/{staffId}：404 / 工号冲突 409 / 注入 userId、status → 400 / 缺必填 422")
    void staffUpdateRejectsInvalidInput() throws Exception {
        SysUser owner = account(STAFF, null);
        Staff staff = staff(owner, newEmployeeNo());
        String takenNo = newEmployeeNo();
        staff(account(STAFF, null), takenNo);

        mvc.perform(put("/api/admin/staff/9223372036854775807").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"无人\",\"employeeNo\":\"EMP-NONE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(put("/api/admin/staff/" + staff.id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"撞号\",\"employeeNo\":\"" + takenNo + "\"}"))
                .andExpect(status().isConflict());
        assertEquals(staff.employeeNo, staffs.selectById(staff.id).employeeNo);
        assertEquals(owner.realName, users.selectById(owner.id).realName);

        // 契约 StaffUpdateRequest 不含 userId/status：注入它们必须被全局 FAIL_ON_UNKNOWN_PROPERTIES 判 400
        mvc.perform(put("/api/admin/staff/" + staff.id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"注入\",\"employeeNo\":\"EMP-X\",\"status\":\"DISABLED\","
                                + "\"userId\":\"" + staff.userId + "\"}"))
                .andExpect(status().isBadRequest());

        // 缺契约 required 的 employeeNo → 422
        mvc.perform(put("/api/admin/staff/" + staff.id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"realName\":\"缺工号\"}"))
                .andExpect(status().isUnprocessableContent());

        assertEquals("ACTIVE", accountStatusOf(staff.userId));
        assertEquals(owner.realName, users.selectById(owner.id).realName);
    }

    @Test
    @DisplayName("PUT /admin/staff/{staffId}：STAFF 越权 → 403 且零写入")
    void staffUpdateRequiresAdmin() throws Exception {
        Staff staff = staff(account(STAFF, null), newEmployeeNo());
        mvc.perform(put("/api/admin/staff/" + staff.id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"越权\",\"employeeNo\":\"EMP-NOPE\"}"))
                .andExpect(status().isForbidden());
        assertEquals(staff.employeeNo, staffs.selectById(staff.id).employeeNo);
    }

    // ===================== PATCH /admin/staff/{staffId}/status =====================

    @Test
    @DisplayName("PATCH /admin/staff/{staffId}/status：启停落到 sys_user，返回 StaffEnvelope")
    void staffStatusTogglesLoginAccount() throws Exception {
        SysUser owner = account(STAFF, PASSWORD);
        Staff staff = staff(owner, newEmployeeNo());
        String victimToken = bearer(owner, STAFF);

        JsonNode disabled = okData(patch("/api/admin/staff/" + staff.id + "/status")
                .header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"));
        assertFieldsExactly(disabled, STAFF_FIELDS, STAFF_REQUIRED, "停用后的 data");
        assertEquals("DISABLED", disabled.get("status").asString());
        assertEquals(0, users.selectById(owner.id).status, "staff 表没有状态列，状态只能落在 sys_user");
        mvc.perform(get("/api/auth/me").header("Authorization", victimToken)).andExpect(status().isUnauthorized());
        login(owner.username, PASSWORD).andExpect(status().isUnauthorized());

        JsonNode enabled = okData(patch("/api/admin/staff/" + staff.id + "/status")
                .header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"));
        assertEquals("ACTIVE", enabled.get("status").asString());
        assertEquals(1, users.selectById(owner.id).status);
        login(owner.username, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /admin/staff/{staffId}/status：账号已失效 → 409 且不写状态")
    void staffStatusConflictsWhenAccountIsGone() throws Exception {
        SysUser owner = account(STAFF, null);
        Staff staff = staff(owner, newEmployeeNo());
        SysUser removed = users.selectById(owner.id);
        removed.deleted = 1;
        users.updateById(removed);

        mvc.perform(patch("/api/admin/staff/" + staff.id + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_ACCOUNT_CONFLICT"));

        assertEquals(1, users.selectById(owner.id).status, "409 之后状态必须原样不动");
    }

    @Test
    @DisplayName("PATCH /admin/staff/{staffId}/status：非法枚举 422、不存在 404、STAFF 403")
    void staffStatusRejectsInvalidInput() throws Exception {
        SysUser owner = account(STAFF, null);
        Staff staff = staff(owner, newEmployeeNo());

        mvc.perform(patch("/api/admin/staff/" + staff.id + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(patch("/api/admin/staff/9223372036854775807/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/admin/staff/" + staff.id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());

        assertEquals(1, users.selectById(owner.id).status);
    }

    // ===================== 夹具与断言工具 =====================

    private SysUser account(String roleCode, String rawPassword) {
        SysUser user = new SysUser();
        user.username = newUsername();
        user.nickname = "契约回归";
        user.realName = "契约回归";
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

    private Staff staff(SysUser owner, String employeeNo) {
        Staff staff = new Staff();
        staff.userId = owner.id;
        staff.employeeNo = employeeNo;
        staff.department = "测试部";
        staff.position = "测试岗";
        staffs.insert(staff);
        return staff;
    }

    private String bearer(SysUser user, String role) {
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of(role));
    }

    private String passwordBody(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    private String staffCreateBody(String username, String employeeNo) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"realName\":\"王顾问\","
                + "\"phone\":\"13800138002\",\"employeeNo\":\"" + employeeNo + "\",\"department\":\"运营部\","
                + "\"position\":\"行程顾问\"}";
    }

    /** 在合法请求体末尾注入契约外字段，用来钉住 additionalProperties: false。 */
    private static String injectStatus(String jsonBody) {
        return jsonBody.substring(0, jsonBody.length() - 1) + ",\"status\":\"ADMIN\"}";
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private String accountStatusOf(Long userId) {
        return users.selectById(userId).status == 1 ? "ACTIVE" : "DISABLED";
    }

    private Set<String> roleCodesOf(Long userId) {
        Set<String> codes = new LinkedHashSet<>();
        for (SysUserRole link : userRoles.selectList(new QueryWrapper<SysUserRole>().eq("user_id", userId))) {
            SysRole role = roles.selectById(link.roleId);
            if (role != null) {
                codes.add(role.code);
            }
        }
        return codes;
    }

    /** 断言 200 并返回 data 节点。 */
    private JsonNode okData(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse();
        return json.readTree(body(response)).get("data");
    }

    /** MockHttpServletResponse 默认按 ISO-8859-1 解码，中文会乱码，必须按 UTF-8 读字节。 */
    private static String body(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static Set<String> names(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.get("id").asString())) {
                return item;
            }
        }
        throw new AssertionError("分页结果里找不到 id=" + id + " 的记录");
    }

    /** 契约是 additionalProperties: false：多字段与少必填字段都算违约，两个方向都要断言。 */
    private static void assertFieldsExactly(JsonNode node, Set<String> allowed, Set<String> required, String label) {
        Set<String> actual = new LinkedHashSet<>();
        node.propertyNames().forEach(actual::add);
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(allowed);
        assertTrue(unexpected.isEmpty(), label + " 出现契约外字段（additionalProperties: false）：" + unexpected);
        for (String field : required) {
            assertTrue(actual.contains(field), label + " 缺少契约必填字段：" + field);
        }
        assertFalse(actual.contains("passwordHash"), label + " 不得出现密码哈希");
        assertNull(node.get("password"), label + " 不得出现密码");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 满足契约 username pattern ^[A-Za-z0-9_]{3,32}$。 */
    private static String newUsername() {
        return "gap_" + shortId();
    }

    private static String newEmployeeNo() {
        return "EMP" + shortId();
    }
}
