package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.*;
import com.travelagency.domain.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
// JwtTokenProvider 会在容器启动时校验签名密钥（缺失即启动失败），
// 这里为测试上下文注入固定密钥，避免集成测试依赖开发者本机的 JWT_SECRET 环境变量。
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        // 显式清空支付宝配置：本类要断言「配置不齐时发起支付 fail-closed」，
        // 不能让开发者本机恰好导出的 ALIPAY_* 环境变量把前提改成「配置齐全」。
        "app.integrations.alipay.gateway-url=",
        "app.integrations.alipay.app-id=",
        "app.integrations.alipay.app-private-key=",
        "app.integrations.alipay.alipay-public-key=",
        "app.integrations.alipay.notify-url="})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class Pr9ContractIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired GuideMapper guides;
    @Autowired TravelGuideArticleMapper articles;
    @Autowired TravelRouteMapper routes;
    @Autowired DepartureMapper departures;
    @Autowired TravelOrderMapper orders;
    @Autowired PaymentMapper payments;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;
    private MockMvc mvc;
    private SysUser admin;
    private String adminToken;
    private String staffToken;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = user();
        adminToken = bearer(admin, "ADMIN");
        staffToken = bearer(user(), "STAFF");
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.username = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        user.nickname = "Regression test";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private String bearer(SysUser user, String role) {
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of(role));
    }

    private Guide guide() {
        Guide guide = new Guide();
        guide.userId = user().id;
        guide.name = "Test guide";
        guide.phone = "13800138000";
        guide.intro = "Old intro";
        guide.status = "ACTIVE";
        guides.insert(guide);
        return guide;
    }

    @Test
    void guideCreationCreatesLoginAndReturnsCreated() throws Exception {
        String name = "guide_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String body = "{\"username\":\"" + name + "\",\"password\":\"Regression123!\",\"name\":\"Test guide\",\"phone\":\"13800138000\"}";
        var response = mvc.perform(post("/api/admin/guides").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var view = json.readTree(response.getContentAsString()).get("data");
        assertEquals(name, view.get("username").asString());
        assertEquals("/api/admin/guides/" + view.get("id").asString(), response.getHeader("Location"));
        assertTrue(passwords.matches("Regression123!", users.selectById(view.get("userId").asString()).passwordHash));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"Regression123!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.roles[0]").value("GUIDE"));
        mvc.perform(post("/api/admin/guides").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void staffCannotCreateOrDisableGuideAccounts() throws Exception {
        Guide guide = guide();
        mvc.perform(post("/api/admin/guides").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff_attempt\",\"password\":\"Regression123!\",\"name\":\"Test\",\"phone\":\"12345\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/guides/" + guide.id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void guideUpdateRetainsAccountAndClearsIntro() throws Exception {
        Guide guide = guide();
        mvc.perform(put("/api/admin/guides/" + guide.id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\",\"phone\":\"123456\",\"intro\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(users.selectById(guide.userId).username));
        assertNull(guides.selectById(guide.id).intro);
    }

    @Test
    void guideUpdateRejectsAccountRebindingAndStatusInjection() throws Exception {
        Guide guide = guide();
        mvc.perform(put("/api/admin/guides/" + guide.id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\",\"phone\":\"123456\",\"userId\":\"" + admin.id + "\",\"status\":\"DISABLED\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(guide.userId, guides.selectById(guide.id).userId);
        assertEquals("ACTIVE", guides.selectById(guide.id).status);
    }

    @Test
    void guideStatusDisablesLoginAccountAndReturnsGuide() throws Exception {
        Guide guide = guide();
        SysUser account = users.selectById(guide.userId);
        account.passwordHash = passwords.encode("Regression123!");
        users.updateById(account);
        String oldToken = bearer(account, "GUIDE");
        mvc.perform(patch("/api/admin/guides/" + guide.id + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"));
        assertEquals(0, users.selectById(guide.userId).status);
        mvc.perform(get("/api/auth/me").header("Authorization", oldToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + account.username + "\",\"password\":\"Regression123!\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/admin/guides/" + guide.id + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INVALID\"}"))
                .andExpect(status().isUnprocessableContent());
        assertEquals("DISABLED", guides.selectById(guide.id).status);
        mvc.perform(patch("/api/admin/guides/" + guide.id + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertEquals(1, users.selectById(guide.userId).status);
    }

    @Test
    void guideInputIsValidatedWithoutCreatingAccounts() throws Exception {
        long before = users.selectCount(null);
        mvc.perform(post("/api/admin/guides").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"short\",\"name\":\"Test\",\"phone\":\"12\"}"))
                .andExpect(status().isUnprocessableContent());
        assertEquals(before, users.selectCount(null));
        Guide guide = guide();
        mvc.perform(put("/api/admin/guides/" + guide.id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Test\"}"))
                .andExpect(status().isUnprocessableContent());
        assertEquals(guide.phone, guides.selectById(guide.id).phone);
    }

    @Test
    void missingGuideUpdateReturnsNotFound() throws Exception {
        mvc.perform(put("/api/admin/guides/9223372036854775807").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Test\",\"phone\":\"12345\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void articleEditRetainsPublicationAndClearsNullableFields() throws Exception {
        TravelGuideArticle article = new TravelGuideArticle();
        article.title = "Test article";
        article.content = "Test content";
        article.summary = "Old summary";
        article.city = "Old city";
        article.coverUrl = "https://example.com/image.png";
        article.status = "PUBLISHED";
        article.authorId = admin.id;
        article.publishedAt = LocalDateTime.of(2026, 9, 17, 12, 0);
        articles.insert(article);
        mvc.perform(put("/api/admin/articles/" + article.id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Updated title\",\"content\":\"Updated content\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        var saved = articles.selectById(article.id);
        assertAll(() -> assertNull(saved.summary), () -> assertNull(saved.city), () -> assertNull(saved.coverUrl));
        assertEquals(article.publishedAt, saved.publishedAt);
        mvc.perform(delete("/api/admin/articles/" + article.id).header("Authorization", adminToken))
                .andExpect(status().isConflict());
    }

    @Test
    void articleInputEnforcesFrozenContract() throws Exception {
        mvc.perform(post("/api/admin/articles").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"content\":\"content\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/admin/articles").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Test\",\"content\":\"content\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/articles").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("title", "Test", "content", "x".repeat(100001)))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void articlePublicationLifecycleUsesDedicatedStatusEndpoint() throws Exception {
        var response = mvc.perform(post("/api/admin/articles").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Test article\",\"content\":\"Test content\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse();
        String id = json.readTree(response.getContentAsString()).get("data").get("id").asString();
        assertEquals("/api/admin/articles/" + id, response.getHeader("Location"));
        mvc.perform(get("/api/articles/" + id)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/admin/articles/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.publishedAt").isNotEmpty());
        mvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/articles/" + id).header("Authorization", staffToken))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/articles/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFLINE\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/articles/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/articles/" + id).header("Authorization", staffToken))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(delete("/api/admin/articles/" + id).header("Authorization", staffToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void paymentRequiresValidIdempotencyHeaderBeforeBusinessLogic() throws Exception {
        mvc.perform(post("/api/orders/TA_missing/pay").header("Authorization", adminToken))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/TA_missing/pay").header("Authorization", adminToken)
                        .header("Idempotency-Key", "short"))
                .andExpect(status().isUnprocessableContent());
    }

    /**
     * 造一张「待支付且已有支付单」的订单，真库写入，跑完随 {@code @Transactional} 回滚。
     *
     * <p>刻意把支付单也造出来（与 {@code OrderService.create} 的真实效果一致）：
     * 这样用例断言的是「配置闸门把请求挡在业务之前」，而不是靠「支付单不存在会先报 404」这种偶然顺序。
     * </p>
     */
    private TravelOrder waitPayOrder(SysUser owner) {
        TravelRoute route = new TravelRoute();
        route.name = "Regression route " + UUID.randomUUID().toString().substring(0, 8);
        route.departureCity = "上海";
        route.destination = "杭州";
        route.durationDays = 2;
        route.status = "PUBLISHED";
        routes.insert(route);

        Departure departure = new Departure();
        departure.routeId = route.id;
        departure.startDate = LocalDate.now().plusDays(30);
        departure.endDate = LocalDate.now().plusDays(31);
        departure.adultPrice = new BigDecimal("1000.00");
        departure.childPrice = new BigDecimal("500.00");
        departure.maxPeople = 10;
        departure.status = "OPEN";
        departures.insert(departure);

        TravelOrder order = new TravelOrder();
        order.orderNo = "TA" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        order.userId = owner.id;
        order.routeId = route.id;
        order.departureId = departure.id;
        order.contactName = "Regression test";
        order.contactPhone = "13800138000";
        order.adultCount = 1;
        order.childCount = 0;
        order.adultUnitPrice = new BigDecimal("1000.00");
        order.childUnitPrice = new BigDecimal("500.00");
        order.totalAmount = new BigDecimal("1000.00");
        order.status = "WAIT_PAY";
        order.paymentStatus = "UNPAID";
        orders.insert(order);

        Payment payment = new Payment();
        payment.orderId = order.id;
        payment.paymentNo = "PAY" + order.orderNo;
        payment.channel = "ALIPAY_SANDBOX";
        payment.amount = order.totalAmount;
        payment.status = "PENDING";
        payments.insert(payment);
        return order;
    }

    /**
     * 支付宝配置不齐时，发起支付必须走真实 HTTP 入口 fail-closed：409 + 明确错误码，
     * 且响应里<b>不能出现任何可付款链接</b>，订单与支付单也不得被推进。
     *
     * <p>这条用例补的是单测覆盖不到的一层：{@code BusinessException} 到契约响应
     * （HTTP 状态码 + {@code code}/{@code message}/{@code data:null} 信封）的映射。</p>
     */
    @Test
    void paymentStartFailsClosedWhenAlipayConfigurationIncomplete() throws Exception {
        SysUser buyer = user();
        String buyerToken = bearer(buyer, "USER");
        TravelOrder order = waitPayOrder(buyer);

        mvc.perform(post("/api/orders/" + order.orderNo + "/pay")
                        .header("Authorization", buyerToken)
                        .header("Idempotency-Key", "regression-pay-not-configured"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message", containsString("支付尚未配置完成")))
                // 提示要点名缺哪一项，运维不必翻代码
                .andExpect(jsonPath("$.message", containsString("ALIPAY_PUBLIC_KEY")))
                // 没有支付跳转信息 —— 这正是「不生成可付款链接」在契约层面的体现
                .andExpect(jsonPath("$.data").value(nullValue()));

        // 被拒绝的请求不得留下任何推进痕迹
        TravelOrder reloaded = orders.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TravelOrder>()
                        .eq("order_no", order.orderNo));
        assertEquals("WAIT_PAY", reloaded.status);
        assertEquals("UNPAID", reloaded.paymentStatus);
        assertEquals(1L, payments.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Payment>()
                        .eq("order_id", order.id)).longValue());
    }
}
