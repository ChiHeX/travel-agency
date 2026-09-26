package com.travelagency;

import com.alipay.api.internal.util.AlipaySignature;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.common.alipay.AlipayGatewayClient;
import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Payment;
import com.travelagency.domain.entity.Refund;
import com.travelagency.domain.entity.Review;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.PaymentMapper;
import com.travelagency.domain.mapper.RefundMapper;
import com.travelagency.domain.mapper.ReviewMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交易全链路契约集成测试（真 MySQL + 真实 HTTP 层）。
 *
 * <p>补的是 {@code CONTRIBUTING.md} §11 要求的「核心业务流程测试」：
 * 创建订单 → 支付结果处理 → 旅行社确认 → 行程开始与完成 → 评价，
 * 外加取消、退款申请 / 同意 / 拒绝、订单状态变化、名额守恒、幂等重放与越权负例。</p>
 *
 * <p><b>为什么必须走真库</b>：{@code OrderService*} 那 5 个单测都是纯 Mockito，
 * 验证的是 Java 分支，绕过了所有由 SQL 承担的正确性——名额守恒的
 * {@code COALESCE / GREATEST} 条件更新、幂等键的唯一约束、退款审核的原子闸门，
 * 以及 Long / 金额 / 时间的序列化形状。这些恰恰是重构中最容易被改坏的地方。</p>
 *
 * <p><b>每个用例的顺序约定</b>：预期抛业务异常的调用一律排在方法末尾。
 * {@code @Transactional} 测试里内层 {@code @Transactional} 方法抛异常后会把参与事务
 * 标记为 rollback-only，此时若再调用另一个内层事务方法并正常返回，Spring 会抛
 * {@code UnexpectedRollbackException}——那是测试脚手架的噪声，不是业务缺陷。
 * 所以「先跑完成功路径，负例收尾」。</p>
 *
 * <p><b>支付宝配置前提由本类自己声明，不依赖运行环境</b>：本类要真的走完「发起支付 → 支付回调」，
 * 而 {@code POST /orders/{orderNo}/pay} 在支付配置不齐时按契约 fail-closed 返回
 * {@code 409 PAYMENT_NOT_CONFIGURED} —— 链接一旦交给用户就代表「这笔单现在可以付款」，
 * 而付款结果只能由支付宝回调 {@code notify_url} 回传再验签，缺任何一项都会造成
 * 「用户付了钱、订单却停在待支付」。所以本类用 {@link #alipayCredentials} 现场生成两对 RSA 密钥
 * （本应用一对、支付宝一对）把四项配置注齐，回调也改为按官方 V1 口径做 RSA2 签名：
 * 配置齐全后 {@code ALIPAY_PUBLIC_KEY} 与 {@code ALIPAY_APP_ID} 同时存在，控制器必然走官方验签路径。
 * 这样本类既不依赖「本机碰巧没配支付宝」，也不再依赖「未配置时返回占位链接」这类旧行为；
 * HMAC 回退通道本身由 {@code PaymentControllerTest} 单独覆盖。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。整个类在事务内执行，
 * 结束时统一回滚，不会给本地库留下任何数据。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        "app.integrations.alipay.callback-secret=trading-flow-callback-secret-32-bytes"
})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class TradingFlowContractIntegrationTest {

    /** 测试用 APPID，只用于校验回调归属，不是真实沙箱账号。 */
    private static final String TEST_APP_ID = "9021000168641134";
    private static final String TEST_NOTIFY_URL = "https://travel-agency.test/api/payments/alipay/notify";
    /** 故意写坏的签名：既不是合法 Base64，也验不过任何公钥。 */
    private static final String BROKEN_SIGNATURE = "AAAAinvalidSignatureAAAA";
    /** 现场生成的两对测试密钥，不落盘、不提交、不联网。 */
    private static final KeyPair APP_KEY_PAIR = keyPair();
    private static final KeyPair ALIPAY_KEY_PAIR = keyPair();

    private static final String ADULT_PRICE = "2999.00";
    private static final String CHILD_PRICE = "1999.00";

    /**
     * 注入一份齐全的支付宝沙箱配置（网关地址走 {@code application.yml} 的默认沙箱地址）。
     *
     * <p>用动态属性而不是 {@code @TestPropertySource} 的字面量，是因为密钥要现场生成、而注解只能写
     * 编译期常量。动态属性在测试环境里优先级最高，所以本机即使导出了 {@code ALIPAY_*} 环境变量，
     * 也改不动本类的前提。</p>
     */
    @DynamicPropertySource
    static void alipayCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.integrations.alipay.app-id", () -> TEST_APP_ID);
        registry.add("app.integrations.alipay.app-private-key",
                () -> base64(APP_KEY_PAIR.getPrivate().getEncoded()));
        registry.add("app.integrations.alipay.alipay-public-key",
                () -> base64(ALIPAY_KEY_PAIR.getPublic().getEncoded()));
        registry.add("app.integrations.alipay.notify-url", () -> TEST_NOTIFY_URL);
    }

    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired GuideMapper guides;
    @Autowired TravelRouteMapper routes;
    @Autowired DepartureMapper departures;
    @Autowired TravelOrderMapper orders;
    @Autowired PaymentMapper payments;
    @Autowired RefundMapper refunds;
    @Autowired ReviewMapper reviews;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    /**
     * 支付宝适配器的 spy：<b>只替换出款那一跳</b>。
     *
     * <p>退款审核现在会真的调 {@code alipay.trade.refund}，而本环境既没有真实的支付宝交易、
     * 也不允许出网，所以那一跳必须被替换成成功响应；其余方法（尤其 {@code verifyNotifySignature}
     * 的官方验签）保持真实 —— 回调验签正是本类要验的东西，换成 mock 就等于没测。</p>
     */
    @MockitoSpyBean AlipayGatewayClient alipayGatewayClient;

    private MockMvc mvc;
    private SysUser buyer;
    private SysUser stranger;
    private String buyerToken;
    private String strangerToken;
    private String adminToken;
    private Long routeId;
    private Long departureId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        buyer = user("buyer");
        stranger = user("stranger");
        buyerToken = bearer(buyer, "USER");
        strangerToken = bearer(stranger, "USER");
        adminToken = bearer(user("admin"), "ADMIN");

        Guide guide = guide();

        TravelRoute route = route();
        routeId = route.id;
        departureId = departure(route.id, guide.id, 20).id;
    }

    // ------------------------------------------------------------------
    // 主链路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("下单→支付回调→确认→出团→完成→评价 全链路按契约推进，名额不丢不重")
    void fullLifecycleFollowsContractFromBookingToReview() throws Exception {
        // 1) 报名：契约是 201 + Location + OrderEnvelope，并在事务内占用名额
        String orderNo = book(orderBody(1, 0), newKey(), 201);
        assertEquals(1, reserved());
        assertEquals(0, confirmed());
        assertEquals(19, availableSeats(), "报名后剩余名额应为 maxPeople - reserved - confirmed");

        // 详情返回契约 OrderDetail：order/route/departure/travelers/refunds 齐备
        JsonNode detail = detail(orderNo);
        assertEquals("WAIT_PAY", detail.at("/order/status").asString());
        assertEquals("UNPAID", detail.at("/order/paymentStatus").asString());
        assertEquals(ADULT_PRICE, detail.at("/order/totalAmount").asString(), "金额应为 2 位小数字符串");
        assertTrue(detail.at("/order/id").isString(), "契约 Id 必须序列化为字符串");
        assertEquals(1, detail.get("travelers").size());
        assertEquals("ADULT", detail.at("/travelers/0/travelerType").asString());
        assertEquals(0, detail.get("refunds").size());
        assertTrue(detail.at("/route/name").isString());
        assertTrue(detail.at("/departure/availableSeats").isNumber());
        assertFalse(detail.get("order").has("version"), "order 应为契约视图而非数据库实体");

        // 2) 发起支付：返回支付跳转信息
        mvc.perform(post("/api/orders/" + orderNo + "/pay").header("Authorization", buyerToken)
                        .header("Idempotency-Key", newKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.channel").value("ALIPAY_SANDBOX"))
                .andExpect(jsonPath("$.data.amount").value(ADULT_PRICE))
                .andExpect(jsonPath("$.data.paymentUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

        // 3) 支付结果处理：验签通过的回调把订单推进到待确认
        assertEquals("success", alipayNotify(orderNo, tradeNo(), "TRADE_SUCCESS", ADULT_PRICE, true));
        detail = detail(orderNo);
        assertEquals("PAID_WAIT_CONFIRM", detail.at("/order/status").asString());
        assertEquals("PAID", detail.at("/payment/status").asString());
        assertTrue(detail.at("/order/paidAt").isString(), "支付后应写入 paidAt");
        assertEquals(ADULT_PRICE, detail.at("/payment/amount").asString());
        assertEquals(1, reserved(), "支付回调不改动名额归属");

        // 4) 旅行社确认：预留名额转正式名额，线路有效报名数 +1
        mvc.perform(post("/api/admin/orders/" + orderNo + "/confirm").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedAt").isNotEmpty());
        assertEquals(0, reserved(), "确认后预留名额应清零");
        assertEquals(1, confirmed(), "确认后正式名额应 +1");
        assertEquals(1, routes.selectById(routeId).validBookingCount.intValue(), "确认后线路有效报名数 +1");

        // 5) 行程推进到已完成（团期端点与其订单级联由 GuideTripContractIntegrationTest 覆盖）
        completeTrip(orderNo);
        assertNotNull(orderOf(orderNo).completedAt, "完成时应写入 completedAt");
        assertEquals(1, confirmed(), "行程推进不应改动名额");

        // 6) 评价：仅已完成订单可评，返回 201 + Location，并重算线路评分
        mvc.perform(post("/api/orders/" + orderNo + "/reviews").header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"content\":\"行程安排合理，导游讲解认真。\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.status").value("VISIBLE"))
                .andExpect(jsonPath("$.data.userNickname").isNotEmpty());

        assertEquals(5, detail(orderNo).at("/review/rating").asInt());
        assertEquals(1, routes.selectById(routeId).ratingCount.intValue(), "评价后线路评价数应为 1");
        assertEquals(0, new BigDecimal("5.00").compareTo(routes.selectById(routeId).ratingAvg),
                "评价后线路平均分应为 5.00");

        // 全链路结束后：名额被正式占用 1 个，预留归零，既没有丢也没有重复占用
        assertEquals(0, reserved());
        assertEquals(1, confirmed());
        assertEquals(1, orders.selectCount(new QueryWrapper<TravelOrder>().eq("order_no", orderNo)).intValue());
    }

    @Test
    @DisplayName("订单列表使用分页信封、按状态过滤，且列表项为契约视图")
    void orderListUsesPaginationEnvelopeAndFiltersByStatus() throws Exception {
        String orderNo = book(orderBody(1, 0), newKey(), 201);

        mvc.perform(get("/api/orders").header("Authorization", buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalPages").isNumber());

        // 前端 api/request.js 只取 body.data，视图统一读 data.items —— 必须是分页信封而非裸数组
        JsonNode data = read(get("/api/orders").header("Authorization", buyerToken)
                .param("status", "WAIT_PAY")).get("data");
        assertTrue(data.has("items"), "订单列表必须是分页信封");
        assertEquals(1, data.get("total").asInt(), "按 WAIT_PAY 过滤应只命中本用例的下单");
        assertEquals(orderNo, data.at("/items/0/orderNo").asString());
        assertEquals("WAIT_PAY", data.at("/items/0/status").asString());
        assertTrue(data.at("/items/0/id").isString(), "契约 Id 必须序列化为字符串");
        assertEquals(ADULT_PRICE, data.at("/items/0/totalAmount").asString(), "金额为 2 位小数字符串");
        assertTrue(data.at("/items/0/routeName").isString(), "列表项应补齐 routeName");
    }

    @Test
    @DisplayName("同一幂等键重复下单只产生一张订单、只占一次名额")
    void orderCreationIsIdempotentForTheSameKey() throws Exception {
        String key = newKey();
        String first = book(orderBody(1, 0), key, 201);
        assertEquals(1, reserved());

        String replayed = book(orderBody(1, 0), key, 201);
        assertEquals(first, replayed, "重复提交必须返回首次生成的订单号");
        assertEquals(1, reserved(), "重放不得重复占用名额");
        assertEquals(1, orders.selectCount(new QueryWrapper<TravelOrder>().eq("order_no", first)).intValue());
    }

    @Test
    @DisplayName("真实签名的收银台链接下同一幂等键重放：同一支付单、同一有效期、指向同一笔支付宝交易")
    void paymentStartIsIdempotentForTheSameKey() throws Exception {
        String orderNo = book(orderBody(1, 0), newKey(), 201);
        String key = newKey();

        JsonNode first = read(post("/api/orders/" + orderNo + "/pay")
                .header("Authorization", buyerToken).header("Idempotency-Key", key)).get("data");
        JsonNode replay = read(post("/api/orders/" + orderNo + "/pay")
                .header("Authorization", buyerToken).header("Idempotency-Key", key)).get("data");

        assertEquals(first.get("paymentNo").asString(), replay.get("paymentNo").asString(),
                "同键重放必须返回首次生成的支付单号");
        assertEquals(first.get("expiresAt").asString(), replay.get("expiresAt").asString(),
                "支付窗口锚定在首次发起支付的时刻，重试不得把它一次次往后顺延");
        assertEquals("ALIPAY_SANDBOX", first.get("channel").asString());
        assertEquals(ADULT_PRICE, first.get("amount").asString());

        // 两次响应里的收银台地址都必须是官方 SDK 真签出来的链接（不是网关占位地址）：
        // 网关 / method / RSA2 签名 / notify_url 参与签名 / out_trade_no 与金额都要能核对。
        Map<String, String> firstParams =
                signedCashierParams(first.get("paymentUrl").asString(), orderNo, ADULT_PRICE);
        Map<String, String> replayParams =
                signedCashierParams(replay.get("paymentUrl").asString(), orderNo, ADULT_PRICE);

        // ⚠️ 幂等保证的是「同一笔支付宝交易」，不是「同一串 URL」：
        // alipay.trade.page.pay 的公共参数含 timestamp，两次签发的 URL 天然不同字符串
        // （实测：间隔 1.5s 必然不同，同一秒内才偶然相同），因此这里绝不断言 URL 逐字相等，
        // 而是对齐真正决定「是哪一笔交易」的三项。
        assertEquals(firstParams.get("biz_content"), replayParams.get("biz_content"),
                "重放必须指向同一笔支付宝交易：out_trade_no / 金额 / 商品名逐字一致");
        assertEquals(firstParams.get("notify_url"), replayParams.get("notify_url"),
                "重放的链接必须仍把支付结果回传到本系统的 notify_url");

        assertEquals("WAIT_PAY", orderOf(orderNo).status, "发起支付不得推进订单状态");
        assertEquals(1, reserved(), "重放不得重复占用名额");
        assertEquals(1, paymentsFor(orderNo).size(), "重放不得产生第二张支付单");
    }

    @Test
    @DisplayName("同一幂等键换个订单号复用直接拒绝，不返回另一笔订单的支付信息")
    void paymentStartRejectsKeyReusedOnAnotherOrder() throws Exception {
        String firstOrder = book(orderBody(1, 0), newKey(), 201);
        String secondOrder = book(orderBody(1, 0), newKey(), 201);
        String key = newKey();

        read(post("/api/orders/" + firstOrder + "/pay")
                .header("Authorization", buyerToken).header("Idempotency-Key", key));

        // 负例排在末尾：业务异常会污染参与事务，之后不能再走成功路径
        mvc.perform(post("/api/orders/" + secondOrder + "/pay")
                        .header("Authorization", buyerToken).header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("取消待支付订单释放预留名额，且同一订单不能取消两次")
    void cancelReleasesReservedSeatAndRejectsSecondCancel() throws Exception {
        String orderNo = book(orderBody(2, 0), newKey(), 201);
        assertEquals(2, reserved());

        mvc.perform(post("/api/orders/" + orderNo + "/cancel").header("Authorization", buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());
        assertEquals(0, reserved(), "取消后预留名额应被释放");
        assertEquals(20, availableSeats(), "释放后剩余名额应回到 20");

        // 重复取消排在末尾：业务异常会污染参与事务，之后不能再走成功路径
        mvc.perform(post("/api/orders/" + orderNo + "/cancel").header("Authorization", buyerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertEquals(0, reserved(), "重复取消不得把名额减成负数");
    }

    @Test
    @DisplayName("名额上限由 SQL 条件更新强制，且释放出来的名额可以被再次占用")
    void capacityLimitIsEnforcedAndReleasedSeatsCanBeReused() throws Exception {
        Departure narrow = departure();
        narrow.maxPeople = 2;
        departures.updateById(narrow);

        // 成功路径先跑完：占满 → 释放 → 再占满
        String first = book(orderBody(2, 0), newKey(), 201);
        assertEquals(2, reserved());
        mvc.perform(post("/api/orders/" + first + "/cancel").header("Authorization", buyerToken))
                .andExpect(status().isOk());
        assertEquals(0, reserved(), "释放后名额应归还");
        book(orderBody(2, 0), newKey(), 201);
        assertEquals(2, reserved(), "归还的名额应可被再次占用");

        // 负例收尾：超出容量的下单必须被原子条件更新拒绝，且不改动名额
        mvc.perform(post("/api/orders").header("Authorization", buyerToken)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(1, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTURE_CAPACITY_INSUFFICIENT"));
        assertEquals(2, reserved(), "失败的下单不得改动名额");
    }

    // ------------------------------------------------------------------
    // 支付回调
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重复支付回调只推进订单一次，不重写支付时间也不二次改名额")
    void repeatedPaymentCallbacksAdvanceOrderOnlyOnce() throws Exception {
        String orderNo = book(orderBody(1, 0), newKey(), 201);
        startPayment(orderNo);
        String tradeNo = tradeNo();

        assertEquals("success", alipayNotify(orderNo, tradeNo, "TRADE_SUCCESS", ADULT_PRICE, true));
        TravelOrder afterFirst = orderOf(orderNo);
        assertEquals("PAID_WAIT_CONFIRM", afterFirst.status);
        assertNotNull(afterFirst.paidAt);

        // 支付宝会重复投递通知；第二次必须被支付单的原子闸门拦下
        assertEquals("success", alipayNotify(orderNo, tradeNo, "TRADE_SUCCESS", ADULT_PRICE, true));
        TravelOrder afterSecond = orderOf(orderNo);
        assertEquals("PAID_WAIT_CONFIRM", afterSecond.status, "重复回调不得把订单再推进一次");
        assertEquals(afterFirst.paidAt, afterSecond.paidAt, "重复回调不得改写首次支付时间");
        assertEquals(1, reserved(), "重复回调不得改名额");
        assertEquals(1, orders.selectCount(new QueryWrapper<TravelOrder>().eq("order_no", orderNo)).intValue());
    }

    @Test
    @DisplayName("回调验签失败、结果非成功、金额不符与缺失金额都被拒绝且订单保持未支付")
    void paymentCallbackRejectsInvalidSignatureAmountMismatchAndMissingAmount() throws Exception {
        String orderNo = book(orderBody(1, 0), newKey(), 201);
        startPayment(orderNo);

        // 签名错误：伪造回调不能让订单变成已支付
        assertEquals("failure", alipayNotify(orderNo, tradeNo(), "TRADE_SUCCESS", ADULT_PRICE, false));
        assertEquals("WAIT_PAY", orderOf(orderNo).status);

        // 结果非成功
        assertEquals("failure", alipayNotify(orderNo, tradeNo(), "TRADE_CLOSED", ADULT_PRICE, true));
        assertEquals("WAIT_PAY", orderOf(orderNo).status);

        // 缺少 total_amount：不能被当成「没有金额可比对」而放行
        assertEquals("failure", alipayNotify(orderNo, tradeNo(), "TRADE_SUCCESS", null, true));
        assertEquals("WAIT_PAY", orderOf(orderNo).status);

        // 金额不符收尾（会走业务层抛异常，故排在最后）
        assertEquals("failure", alipayNotify(orderNo, tradeNo(), "TRADE_SUCCESS", "1.00", true));
        assertEquals("WAIT_PAY", orderOf(orderNo).status);
        assertEquals(1, reserved());
    }

    // ------------------------------------------------------------------
    // 后台确认
    // ------------------------------------------------------------------

    @Test
    @DisplayName("确认报名要求 STAFF/ADMIN 权限、订单已支付且团期未关闭")
    void confirmRequiresStaffRolePaidOrderAndOpenDeparture() throws Exception {
        // 三类数据先备齐，避免异常污染事务后再走成功路径
        String unpaid = book(orderBody(1, 0), newKey(), 201);
        String paid = book(orderBody(1, 0), newKey(), 201);
        String blocked = book(orderBody(1, 0), newKey(), 201);
        settlePayment(paid);
        settlePayment(blocked);

        // 普通用户拿不到后台权限（Security 层拦截，不进业务方法）
        mvc.perform(post("/api/admin/orders/" + paid + "/confirm").header("Authorization", buyerToken))
                .andExpect(status().isForbidden());

        // 成功路径：已支付 + 团期开放 → 确认通过
        mvc.perform(post("/api/admin/orders/" + paid + "/confirm").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertEquals(1, confirmed());
        assertEquals(2, reserved(), "另两张未确认订单仍占用预留名额");

        // 以下均为负例，逐条独立、且之间不再插入成功路径

        // 已确认的订单不能重复确认
        mvc.perform(post("/api/admin/orders/" + paid + "/confirm").header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertEquals(1, confirmed(), "重复确认不得把名额加两次");

        // 未支付订单不能确认
        mvc.perform(post("/api/admin/orders/" + unpaid + "/confirm").header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));

        // 团期关闭后不能再确认报名
        Departure closed = departure();
        closed.status = "CLOSED";
        departures.updateById(closed);
        mvc.perform(post("/api/admin/orders/" + blocked + "/confirm").header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertEquals(1, routes.selectById(routeId).validBookingCount.intValue(),
                "只有成功确认的那一单计入有效报名数，失败的确认不得回填");
    }

    // ------------------------------------------------------------------
    // 退款
    // ------------------------------------------------------------------

    @Test
    @DisplayName("退款审核通过释放正式名额并回退线路有效报名数")
    void refundApprovalReleasesConfirmedSeatAndReversesBookingCount() throws Exception {
        String orderNo = confirmedOrder();

        JsonNode applied = read(post("/api/orders/" + orderNo + "/refunds").header("Authorization", buyerToken)
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"出行时间冲突，申请退款。\"}"), 201).get("data");
        assertEquals("APPLYING", applied.get("status").asString());
        assertEquals("CONFIRMED", applied.get("originalOrderStatus").asString(), "应记录原业务状态以便驳回时恢复");
        assertEquals(ADULT_PRICE, applied.get("amount").asString());
        assertTrue(applied.get("id").isString(), "契约 Id 必须序列化为字符串");
        String refundId = applied.get("id").asString();
        assertEquals("REFUND_APPLYING", orderOf(orderNo).status, "申请后订单应进入退款申请中");
        assertEquals(1, confirmed(), "申请阶段还不释放名额");

        // 成功路径：审核通过。出款那一跳替换为支付宝成功响应（见 givenPayoutSucceeds）
        givenPayoutSucceeds(orderNo);
        mvc.perform(post("/api/admin/refunds/" + refundId + "/approve").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"已核验订单和退款条件。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.reviewedBy").isNotEmpty())
                .andExpect(jsonPath("$.data.reviewedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.reviewComment").value("已核验订单和退款条件。"));

        // 出款参数：商户订单号＝orderNo，请求号＝RF+退款单主键（支付宝侧的退款幂等键）
        verify(alipayGatewayClient).refund(eq(orderNo), eq("RF" + refundId),
                eq(new BigDecimal(ADULT_PRICE)), anyString());

        TravelOrder refunded = orderOf(orderNo);
        assertEquals("REFUNDED", refunded.status);
        assertEquals("REFUNDED", refunded.paymentStatus);
        assertEquals(0, confirmed(), "退款通过应释放正式名额");
        assertEquals(0, routes.selectById(routeId).validBookingCount.intValue(), "退款通过应回退线路有效报名数");

        // 负例收尾：已处理的退款单不能再审一次，更不能把名额减两次
        mvc.perform(post("/api/admin/refunds/" + refundId + "/approve").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"重复审核\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_STATE_CONFLICT"));
        assertEquals(0, confirmed(), "重复审核不得把名额减两次");
    }

    @Test
    @DisplayName("出款结果未确认：503 REFUND_RESULT_UNCONFIRMED，且不落已退款、不动订单与名额")
    void refundReportsPendingConfirmationWhenPayoutOutcomeIsUnknown() throws Exception {
        String orderNo = confirmedOrder();
        String refundId = read(post("/api/orders/" + orderNo + "/refunds").header("Authorization", buyerToken)
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"这次出款结果拿不到确定结论。\"}"), 201).get("data").get("id").asString();

        // 出款那一跳替换成「结果未确认」：请求超时、且用同一请求号查询也没查到结论
        givenPayoutUnconfirmed();

        mvc.perform(post("/api/admin/refunds/" + refundId + "/approve").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"第一次尝试\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REFUND_RESULT_UNCONFIRMED"));

        // 没确认钱退出去，就不能有任何「已退款」痕迹
        assertEquals("REFUND_APPLYING", orderOf(orderNo).status, "结果未确认不得改动订单状态");
        assertNotEquals("REFUNDED", refundStatus(refundId), "结果未确认不得落 REFUNDED");
        assertEquals(1, confirmed(), "结果未确认不得释放名额");
        // ⚠️ 「退款单是否真的留在 PROCESSING（即事务是否提交而非回滚）」无法在本类里断言：
        // 本类是 @Transactional 测试，内层参与事务不会真正提交。那条语义由真机实测覆盖
        // （见 PR 描述里的实测记录）。
    }

    @Test
    @DisplayName("退款待确认期间拒绝被拦下，订单不被恢复；确认成功后释放名额")
    void refundRejectionIsRefusedWhilePayoutResultIsUnconfirmed() throws Exception {
        String orderNo = confirmedOrder();
        String refundId = read(post("/api/orders/" + orderNo + "/refunds").header("Authorization", buyerToken)
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"支付宝侧其实可能已经退款成功。\"}"), 201).get("data").get("id").asString();

        // 直接构造「出款已发起、结果待确认」这个持久状态：
        // 真实链路由「出款请求与随后的结果查询都超时」产生，这里只关心它之后的行为。
        refunds.update(null, new UpdateWrapper<Refund>()
                .eq("id", Long.valueOf(refundId))
                .set("status", "PROCESSING"));

        // 钱可能已经退出去，此时「拒绝」必须被拦住 —— 拒绝会把订单恢复成申请前的已支付状态
        mvc.perform(post("/api/admin/refunds/" + refundId + "/reject").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"材料不齐，驳回\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_RESULT_UNCONFIRMED"));
        assertEquals("REFUND_APPLYING", orderOf(orderNo).status, "被拦下的拒绝绝不能让订单回到已支付");
        assertEquals("PROCESSING", refundStatus(refundId), "被拦下的拒绝不得改动退款单");
        assertEquals(1, confirmed(), "被拦下的拒绝不得释放名额");

        // 继续确认的入口是重试「同意」：仍用同一请求号，这次支付宝给出确定成功 ⇒ 收敛
        givenPayoutSucceeds(orderNo);
        mvc.perform(post("/api/admin/refunds/" + refundId + "/approve").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"重试确认\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"));
        assertEquals(0, confirmed(), "确认成功后应释放名额");
        verify(alipayGatewayClient).refund(eq(orderNo), eq("RF" + refundId),
                eq(new BigDecimal(ADULT_PRICE)), anyString());
    }

    @Test
    @DisplayName("退款被驳回时订单恢复原业务状态且名额、报名数均不变")
    void refundRejectionRestoresOriginalOrderStatus() throws Exception {
        String orderNo = confirmedOrder();

        String refundId = read(post("/api/orders/" + orderNo + "/refunds").header("Authorization", buyerToken)
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"临时有事，申请退款。\"}"), 201).at("/data/id").asString();

        mvc.perform(post("/api/admin/refunds/" + refundId + "/reject").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"已过可退期限。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewComment").value("已过可退期限。"));

        assertEquals("CONFIRMED", orderOf(orderNo).status, "驳回后订单应恢复原业务状态");
        assertEquals("PAID", orderOf(orderNo).paymentStatus, "驳回不得改动支付状态");
        assertEquals(1, confirmed(), "驳回不得释放名额");
        assertEquals(1, routes.selectById(routeId).validBookingCount.intValue(), "驳回不得回退有效报名数");

        // 契约 additionalProperties:false：多传字段应是 400（Jackson 层），而非静默忽略
        mvc.perform(post("/api/admin/refunds/" + refundId + "/reject").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"再试一次\",\"status\":\"REFUNDED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("退款申请受同键幂等、重复申请与订单归属三重约束")
    void refundApplicationIsGuardedByIdempotencyDuplicatesAndOwnership() throws Exception {
        String orderNo = confirmedOrder();
        String key = newKey();
        String body = "{\"reason\":\"行程有变，申请退款。\"}";

        // 首次申请
        String refundId = read(post("/api/orders/" + orderNo + "/refunds").header("Authorization", buyerToken)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body), 201).at("/data/id").asString();
        assertEquals(1, refunds.selectCount(new QueryWrapper<Refund>().eq("order_id", orderOf(orderNo).id)).intValue());

        // 同一幂等键重放：应返回同一条退款记录，而不是再插一条
        String replayed = read(post("/api/orders/" + orderNo + "/refunds").header("Authorization", buyerToken)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body), 201).at("/data/id").asString();
        assertEquals(refundId, replayed, "同键重放必须返回同一退款单");
        assertEquals(1, refunds.selectCount(new QueryWrapper<Refund>().eq("order_id", orderOf(orderNo).id)).intValue(),
                "重放不得产生第二条退款记录");

        // 换一个幂等键也不行：订单已随首次申请进入 REFUND_APPLYING，第二次申请必须被拒。
        // 这里返回的是 ORDER_STATE_CONFLICT，而不是实现里那条 REFUND_ALREADY_APPLYING ——
        // applyRefund 先校验订单状态、后查是否已有处理中申请，而申请成功时订单已同事务改为
        // REFUND_APPLYING，所以「已有处理中申请」这一分支对同一订单实际不可达。
        // 契约未定义该错误码，故不构成契约偏差，仅记录实现现状。
        mvc.perform(post("/api/orders/" + orderNo + "/refunds").header("Authorization", buyerToken)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"换个理由再申请一次。\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertEquals(1, refunds.selectCount(new QueryWrapper<Refund>().eq("order_id", orderOf(orderNo).id)).intValue());

        // 负例收尾：他人无权对不属于自己的订单申请退款
        mvc.perform(post("/api/orders/" + orderNo + "/refunds").header("Authorization", strangerToken)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"越权尝试退款。\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertEquals(1, refunds.selectCount(new QueryWrapper<Refund>().eq("order_id", orderOf(orderNo).id)).intValue());
    }

    @Test
    @DisplayName("未完成行程的订单不能申请退款")
    void refundRejectsOrdersThatAreNotPayableOrConfirmed() throws Exception {
        String orderNo = book(orderBody(1, 0), newKey(), 201);

        // 未支付订单：契约只允许 PAID_WAIT_CONFIRM / CONFIRMED 申请退款
        mvc.perform(post("/api/orders/" + orderNo + "/refunds").header("Authorization", buyerToken)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"还没付款就想退。\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertEquals(0, refunds.selectCount(new QueryWrapper<Refund>().eq("order_id", orderOf(orderNo).id)).intValue());
    }

    // ------------------------------------------------------------------
    // 评价
    // ------------------------------------------------------------------

    @Test
    @DisplayName("评价要求订单已完成、只能提交一次，越权与他人订单均被拒")
    void reviewRequiresCompletedOrderOwnershipAndUniqueness() throws Exception {
        String orderNo = confirmedOrder();
        completeTrip(orderNo);

        // 评分越界与空内容在进入业务方法前就被拦下（不污染事务）
        mvc.perform(post("/api/orders/" + orderNo + "/reviews").header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":6,\"content\":\"超出范围。\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/orders/" + orderNo + "/reviews").header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4,\"content\":\"\"}"))
                .andExpect(status().isUnprocessableContent());

        // 成功路径
        mvc.perform(post("/api/orders/" + orderNo + "/reviews").header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4,\"content\":\"整体满意。\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rating").value(4))
                .andExpect(jsonPath("$.data.routeId").value(String.valueOf(routeId)));
        assertEquals(1, reviews.selectCount(new QueryWrapper<Review>().eq("order_id", orderOf(orderNo).id)).intValue());

        // 以下为负例，全部排在成功路径之后

        // 每个订单只能评价一次
        mvc.perform(post("/api/orders/" + orderNo + "/reviews").header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"content\":\"再评一次。\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_EXISTS"));
        assertEquals(1, reviews.selectCount(new QueryWrapper<Review>().eq("order_id", orderOf(orderNo).id)).intValue());

        // 越权评价他人订单
        mvc.perform(post("/api/orders/" + orderNo + "/reviews").header("Authorization", strangerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":1,\"content\":\"恶意差评。\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 越权查看他人订单详情
        mvc.perform(get("/api/orders/" + orderNo).header("Authorization", strangerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 不存在的订单
        mvc.perform(get("/api/orders/TA_NOT_EXIST_000000").header("Authorization", buyerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("未完成行程的订单不能评价")
    void reviewRejectsOrdersThatAreNotCompleted() throws Exception {
        String orderNo = confirmedOrder();

        mvc.perform(post("/api/orders/" + orderNo + "/reviews").header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"content\":\"行程不错。\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertEquals(0, reviews.selectCount(new QueryWrapper<Review>().eq("order_id", orderOf(orderNo).id)).intValue());
    }

    @Test
    @DisplayName("缺少必填的 Idempotency-Key 或长度不合法时请求在业务层之前被拒")
    void idempotencyKeyIsEnforcedBeforeBusinessLogic() throws Exception {
        // 缺失必填头
        mvc.perform(post("/api/orders").header("Authorization", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(1, 0)))
                .andExpect(status().isBadRequest());
        // 过短（契约要求 8..128）
        mvc.perform(post("/api/orders").header("Authorization", buyerToken)
                        .header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(1, 0)))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, reserved(), "被拒的下单不得占用名额");
    }

    /**
     * 已经出发的团期不能报名，即使它仍然是 {@code OPEN}、并且客户端直接提交团期 id。
     *
     * <p>公开线路列表已经按 {@code start_date >= CURRENT_DATE} 过滤，正常浏览发现不了过期团期；
     * 但团期 id 是可以被直接提交的，所以"前端不展示"不构成防线。
     * 这里直接向下单接口提交一个仍为 OPEN、出发日期已过去的团期 id。</p>
     *
     * <p>断言被拒之后不留任何痕迹：没有订单、没有支付单、名额一点没动
     * （占名额的条件 UPDATE 里也带了 {@code start_date >= CURRENT_DATE}，
     * 因此即便应用层预检被并发竞态绕过，也不会真的占走名额）。</p>
     */
    @Test
    @DisplayName("已过出发日期的 OPEN 团期不能下单：直传 id 也被拒，且不留订单、支付单与名额变化")
    void rejectsOrderingAnAlreadyDepartedDeparture() throws Exception {
        // 状态保持"报名中"，只把日期挪到过去。
        Departure departed = departure();
        departed.startDate = LocalDate.now().minusDays(3);
        departed.endDate = LocalDate.now().minusDays(1);
        departures.updateById(departed);

        mvc.perform(post("/api/orders").header("Authorization", buyerToken)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(1, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTURE_DEPARTED"));

        assertEquals(0, reserved(), "被拒的下单不得占用预留名额");
        assertEquals(0, confirmed(), "被拒的下单不得改动确认名额");
        assertEquals(0, orders.selectCount(
                        new QueryWrapper<TravelOrder>().eq("departure_id", departureId)).intValue(),
                "被拒的下单不得留下订单");
        assertEquals(0, payments.selectCount(new QueryWrapper<Payment>()
                        .inSql("order_id", "SELECT id FROM travel_order WHERE departure_id = " + departureId))
                        .intValue(),
                "被拒的下单不得留下支付单");
    }

    /** 当天出发的团期仍可下单：口径与公开列表一致（{@code start_date >= CURRENT_DATE}）。 */
    @Test
    @DisplayName("当天出发的 OPEN 团期仍可下单（口径与公开列表一致）")
    void stillAcceptsOrderingADepartureLeavingToday() throws Exception {
        Departure today = departure();
        today.startDate = LocalDate.now();
        today.endDate = LocalDate.now().plusDays(2);
        departures.updateById(today);

        book(orderBody(1, 0), newKey(), 201);

        assertEquals(1, reserved(), "当天出发的团期应当可以正常下单");
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    /** 查订单实体，断言存在。 */
    private TravelOrder orderOf(String orderNo) {
        TravelOrder found = orders.selectOne(new QueryWrapper<TravelOrder>().eq("order_no", orderNo));
        assertNotNull(found, "订单 " + orderNo + " 应存在");
        return found;
    }

    private Departure departure() {
        Departure found = departures.selectById(departureId);
        assertNotNull(found, "团期应存在");
        return found;
    }

    private int reserved() {
        return departure().reservedPeople;
    }

    private int confirmed() {
        return departure().confirmedPeople;
    }

    /** 剩余名额按契约口径自行推导，避免依赖视图层字段。 */
    private int availableSeats() {
        Departure current = departure();
        return current.maxPeople - current.reservedPeople - current.confirmedPeople;
    }

    /** 某张订单下的支付单，用于验证「重放不得产生第二张支付单」。 */
    private List<Payment> paymentsFor(String orderNo) {
        return payments.selectList(new QueryWrapper<Payment>().eq("order_id", orderOf(orderNo).id));
    }

    /**
     * 解开收银台链接的查询串，并断言它是一条<b>真实签发</b>的 {@code alipay.trade.page.pay} 请求。
     *
     * <p>同步 dev 之后收银台地址不再是我们自己拼的网关占位地址，而是官方 SDK 的
     * {@code pageExecute} 组装并 RSA2 签名的结果，因此只判「非空」等于没验证：这里逐项核对
     * 网关、{@code method}、{@code sign_type}、签名本体、{@code app_id}，以及
     * {@code notify_url} 是否参与签名、{@code biz_content} 里的 {@code out_trade_no} 与金额是否正确。</p>
     *
     * @return 解码后的查询参数，供调用方继续对齐「是不是同一笔交易」
     */
    private Map<String, String> signedCashierParams(String paymentUrl, String orderNo, String amount)
            throws Exception {
        assertTrue(paymentUrl.startsWith("https://openapi-sandbox.dl.alipaydev.com/gateway.do?"),
                "收银台地址必须落在支付宝沙箱网关上，实际是：" + paymentUrl);

        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : paymentUrl.substring(paymentUrl.indexOf('?') + 1).split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }

        assertEquals("alipay.trade.page.pay", params.get("method"), "必须是电脑网站支付");
        assertEquals("RSA2", params.get("sign_type"), "契约要求 RSA2");
        assertEquals(TEST_APP_ID, params.get("app_id"), "app_id 必须是本应用");
        assertFalse(params.get("sign") == null || params.get("sign").isBlank(),
                "收银台链接必须带签名，否则不是真实签发");
        assertEquals(TEST_NOTIFY_URL, params.get("notify_url"),
                "notify_url 必须出现在链接里（参与签名），否则付款结果回不来");
        assertFalse(params.get("timestamp") == null || params.get("timestamp").isBlank(),
                "签名必须带 timestamp");

        JsonNode biz = json.readTree(params.get("biz_content"));
        assertEquals(orderNo, biz.get("out_trade_no").asString(),
                "out_trade_no 必须是订单号，支付宝回调才能把交易号对回订单");
        assertEquals(amount, biz.get("total_amount").asString(), "金额必须与订单应付一致");
        return params;
    }

    /** 拉订单详情并校验信封，返回契约 data 节点。 */
    private JsonNode detail(String orderNo) throws Exception {
        return read(get("/api/orders/" + orderNo).header("Authorization", buyerToken)).get("data");
    }

    private JsonNode read(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return read(request, 200);
    }

    /**
     * 执行请求并返回解析后的信封，同时校验状态码与成功信封的三条不变量。
     *
     * <p>状态码由调用方指定：契约里创建类操作是 201（订单 / 退款 / 评价），查询是 200。</p>
     */
    private JsonNode read(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
        JsonNode envelope = json.readTree(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
        assertEquals("OK", envelope.get("code").asString(), "响应信封 code 必须为 OK");
        assertEquals(0, envelope.get("errors").size(), "成功响应的 errors 必须为空数组");
        assertFalse(envelope.get("traceId").asString().isBlank(), "信封必须携带 traceId");
        return envelope;
    }

    // ------------------------------------------------------------------
    // 交易动作辅助
    // ------------------------------------------------------------------

    /** 下单并断言期望状态码与 201 语义，返回订单号。 */
    private String book(String requestBody, String idempotencyKey, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/orders")
                        .header("Authorization", buyerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("WAIT_PAY"))
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.departureId").value(String.valueOf(departureId)))
                .andReturn().getResponse();
        String orderNo = json.readTree(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8))
                .at("/data/orderNo").asString();
        assertEquals("/api/orders/" + orderNo, response.getHeader("Location"), "201 必须带契约约定的 Location");
        return orderNo;
    }

    private void startPayment(String orderNo) throws Exception {
        mvc.perform(post("/api/orders/" + orderNo + "/pay").header("Authorization", buyerToken)
                        .header("Idempotency-Key", newKey()))
                .andExpect(status().isOk());
    }

    /** 走到「待确认」：发起支付 + 一次合法回调。 */
    private void settlePayment(String orderNo) throws Exception {
        startPayment(orderNo);
        assertEquals("success", alipayNotify(orderNo, tradeNo(), "TRADE_SUCCESS", ADULT_PRICE, true));
        assertEquals("PAID_WAIT_CONFIRM", orderOf(orderNo).status);
    }

    /** 走到已确认：下单 → 支付 → 后台确认。 */
    private String confirmedOrder() throws Exception {
        String orderNo = book(orderBody(1, 0), newKey(), 201);
        settlePayment(orderNo);
        mvc.perform(post("/api/admin/orders/" + orderNo + "/confirm").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        return orderNo;
    }

    /**
     * 把订单推进到「行程已完成」这一前置状态。
     *
     * <p>这里直接改库，而不再走 {@code POST /guide/departures/{id}/start|complete}：
     * 那两个端点属于导游端团期生命周期（另一条分支的工作），本类只负责交易域，
     * 把未合并的端点耦合进断言，会让「测试失败」无法区分「交易域回归」与「那两步还没上线」。
     * 团期端点的契约与其对订单状态的级联已由 {@code GuideTripContractIntegrationTest} 覆盖。</p>
     */
    private void completeTrip(String orderNo) {
        Departure finished = departure();
        finished.status = "FINISHED";
        departures.updateById(finished);

        TravelOrder order = orderOf(orderNo);
        order.status = "COMPLETED";
        order.completedAt = LocalDateTime.now();
        orders.updateById(order);
        assertEquals("COMPLETED", orderOf(orderNo).status, "前置条件：订单应已完成为待评价状态");
    }

    /**
     * 模拟支付宝异步通知。
     *
     * <p>签名按<b>官方 V1 口径</b>（{@code getSignCheckContentV1} + {@code rsaSign}）现场算出，
     * 与 {@code AlipayGatewayClient} 内部调用的 {@code rsaCheckV1} 同源；报文里还带 {@code app_id}，
     * 因为配置齐全后控制器会核对通知声明的归属。</p>
     *
     * @param validSignature false 时故意写坏签名，用于验证伪造回调被拒
     * @param amount         null 表示不携带金额字段
     * @return 契约约定的 text/plain 确认文本（success / failure）
     */
    private String alipayNotify(String orderNo, String tradeNo, String result, String amount,
                                boolean validSignature) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("orderNo", orderNo);
        params.put("tradeNo", tradeNo);
        params.put("result", result);
        params.put("app_id", TEST_APP_ID);
        if (amount != null) {
            params.put("total_amount", amount);
        }
        params.put("sign_type", "RSA2");
        // sign 必须在算完签名之后才放进 Map：SDK 的签名内容取自「除 sign / sign_type 之外的参数」
        params.put("sign", validSignature ? notifySignature(params) : BROKEN_SIGNATURE);

        var request = post("/api/payments/alipay/notify")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        params.forEach(request::param);
        MockHttpServletResponse response = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn().getResponse();
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 用「支付宝侧」私钥按官方口径对通知签名。
     *
     * <p>刻意不自己拼串：签名口径必须与 {@code rsaCheckV1} 完全同源，否则用例就成了「自证自话」。
     * {@code getSignCheckContentV1} 会<b>就地删除</b>传入 Map 的 {@code sign}/{@code sign_type}，
     * 所以这里传副本，避免把调用方的参数表改坏。</p>
     */
    private static String notifySignature(Map<String, String> params) throws Exception {
        return AlipaySignature.rsaSign(
                AlipaySignature.getSignCheckContentV1(new LinkedHashMap<>(params)),
                base64(ALIPAY_KEY_PAIR.getPrivate().getEncoded()), "utf-8", "RSA2");
    }

    /** 现场生成 RSA 密钥对，测试不联网、也不依赖仓库里提交的密钥。 */
    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成测试用 RSA 密钥对", ex);
        }
    }

    private static String base64(byte[] der) {
        return Base64.getEncoder().encodeToString(der);
    }

    // ------------------------------------------------------------------
    // 测试数据
    // ------------------------------------------------------------------

    private SysUser user(String prefix) {
        SysUser user = new SysUser();
        user.username = prefix + "_" + randomToken(12);
        user.nickname = "交易链路测试";
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
        guide.userId = user("guide").id;
        guide.name = "测试导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        return guide;
    }

    private TravelRoute route() {
        TravelRoute route = new TravelRoute();
        route.name = "交易契约线路";
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 6;
        route.status = "PUBLISHED";
        route.ratingAvg = new BigDecimal("0.00");
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        return route;
    }

    private Departure departure(Long routeId, Long guideId, int maxPeople) {
        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(20);
        departure.endDate = LocalDate.now().plusDays(25);
        departure.adultPrice = new BigDecimal(ADULT_PRICE);
        departure.childPrice = new BigDecimal(CHILD_PRICE);
        departure.maxPeople = maxPeople;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 0;
        departure.guideId = guideId;
        departure.status = "OPEN";
        departure.version = 0;
        departures.insert(departure);
        return departure;
    }

    /** 构造契约 OrderCreateRequest（additionalProperties:false，字段与冻结契约逐一对齐）。 */
    private String orderBody(int adults, int children) throws Exception {
        List<Map<String, Object>> travelers = new ArrayList<>();
        for (int i = 0; i < adults + children; i++) {
            boolean adult = i < adults;
            travelers.add(Map.<String, Object>of(
                    "travelerType", adult ? "ADULT" : "CHILD",
                    "name", "出行人" + i,
                    "gender", "MALE",
                    "birthDate", adult ? "1990-01-01" : "2015-01-01",
                    "idType", "CHINESE_ID_CARD",
                    "idNo", "31010119900101" + randomToken(4).toUpperCase(),
                    "emergencyName", "紧急联系人",
                    "emergencyPhone", "13800000000"));
        }
        return json.writeValueAsString(Map.<String, Object>of(
                "departureId", String.valueOf(departureId),
                "contactName", "测试联系人",
                "contactPhone", "13800138000",
                "adultCount", adults,
                "childCount", children,
                "travelers", travelers));
    }

    /**
     * 让「退款出款」这一跳返回支付宝成功响应。
     *
     * <p>用 {@code doReturn(...).when(spy)} 而<b>不是</b> {@code when(spy.refund(...))}：
     * 后者在<b>打桩阶段</b>就会真调一次被测方法（Mockito spy 的经典坑），
     * 在这个类里等于当场发起一次真实网络请求。</p>
     */
    private void givenPayoutSucceeds(String orderNo) {
        doReturn(AlipayGatewayClient.RefundResult.succeeded("2027030122001400000000000001", orderNo))
                .when(alipayGatewayClient)
                .refund(anyString(), anyString(), any(BigDecimal.class), anyString());
    }

    /**
     * 让「退款出款」这一跳返回<b>结果未确认</b>：退款请求异常（如超时），且用同一个
     * {@code out_request_no} 查询也没查到 {@code REFUND_SUCCESS}。
     *
     * <p>它对应「支付宝侧其实可能已经退款成功、只是我们没拿到结论」这一类情形，
     * 与「支付宝明确拒绝」是两回事，调用方的处置也不同。</p>
     */
    private void givenPayoutUnconfirmed() {
        doReturn(AlipayGatewayClient.RefundResult.unconfirmed(
                "退款请求异常：Read timed out；查询也失败：Read timed out"))
                .when(alipayGatewayClient)
                .refund(anyString(), anyString(), any(BigDecimal.class), anyString());
    }

    /** 直接读退款单的库内状态，用于断言审核链路对它的处置。 */
    private String refundStatus(String refundId) {
        Refund refund = refunds.selectById(Long.valueOf(refundId));
        assertNotNull(refund, "退款单应存在");
        return refund.status;
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String tradeNo() {
        return "2026" + randomToken(16).toUpperCase();
    }

    private static String randomToken(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
