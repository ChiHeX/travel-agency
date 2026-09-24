package com.travelagency.common.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.travelagency.common.exception.RefundPendingConfirmationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 支付宝沙箱适配器的签名/验签契约，<b>签名与验签全部以官方 SDK {@code AlipaySignature} 为准</b>。
 *
 * <p>这些用例不联网：密钥对在测试内用 {@link KeyPairGenerator} 现场生成，应用侧与支付宝侧
 * 各用一对，分别模拟「本应用签名」与「支付宝签名」。断言不依赖我们自己的拼串实现，
 * 而是拿官方 SDK 的验签函数回验产出，因此实现若偏离官方口径会直接变红。</p>
 *
 * <p>两套口径必须分清，这是本类最容易踩的坑，也是下面成对断言要钉住的东西：</p>
 * <ul>
 *   <li><b>请求签名（收银台链接）</b>：剔除 {@code sign}，{@code sign_type} <b>参与</b>签名
 *       → 对应 {@code rsaCheckV2}。<b>实测</b>：收银台链接用 {@code rsaCheckV1} 回验必然为 false。</li>
 *   <li><b>异步通知验签</b>：剔除 {@code sign} <b>和</b> {@code sign_type}
 *       （等价官方 {@code rsaCheckV1} / {@code getSignCheckContentV1}）。</li>
 * </ul>
 *
 * <p>另外两条实测结论被写成用例，防止后人改回去：</p>
 * <ul>
 *   <li>官方 {@code getSignCheckContentV1/V2} 会<b>就地删除</b>传入 Map 的 {@code sign}/{@code sign_type}，
 *       且删除是<b>无条件</b>的（验签失败也照删）⇒ 实现必须传副本，否则同一份参数验第二次就废了；</li>
 *   <li>公钥为空、{@code sign} 非法 Base64 等情况下 SDK <b>抛 {@code AlipayApiException} 而不是返回 false</b>
 *       ⇒ 实现必须捕获并当作验签失败，否则回调会被打成 5xx。</li>
 * </ul>
 */
class AlipayGatewayClientTest {

    private static final String GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final String APP_ID = "9021000168641134";
    private static final String OTHER_APP_ID = "9999999999999999";
    private static final String NOTIFY_URL = "https://travel-agency.test/api/payments/alipay/notify";
    private static final String SELLER_ID = "2088000000000000";
    private static final String OTHER_SELLER_ID = "2088000000000099";
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ------------------------------------------------------------------ 配置判定

    @Test
    @DisplayName("APPID 与应用私钥齐备时才能生成收银台地址")
    void requiresAppIdAndPrivateKeyToBuildCashierUrl() {
        KeyPair app = keyPair();

        assertTrue(sandbox(privateKey(app), APP_ID).canBuildCashierUrl());
        // APPID 与私钥都给全时才是「可生成」
        assertTrue(gateway(privateKey(app), "", NOTIFY_URL, "").canBuildCashierUrl());
        // 缺少 APPID：登录沙箱页面前就是这种状态，必须回退占位而不是抛异常
        assertFalse(sandbox("", APP_ID).canBuildCashierUrl());
        // 缺少应用私钥
        assertFalse(sandbox(privateKey(app), "").canBuildCashierUrl());
        // 网关地址为空
        assertFalse(new AlipayGatewayClient("", APP_ID, privateKey(app), "", NOTIFY_URL, "")
                .canBuildCashierUrl());
    }

    @Test
    @DisplayName("必须支付宝公钥与 APPID 同时配置才算具备官方验签能力")
    void requiresBothPublicKeyAndAppIdToVerifyNotify() {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();

        // 只有 APPID，没有公钥：验不了签
        assertFalse(sandbox(privateKey(app), APP_ID).canVerifyNotifySignature());
        // 只有公钥，没有 APPID：无法核对通知归属，同样不算具备验签能力
        assertFalse(new AlipayGatewayClient(GATEWAY, "", privateKey(app), publicKey(alipay), NOTIFY_URL, "")
                .canVerifyNotifySignature());
        // 两者齐备
        assertTrue(gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, "").canVerifyNotifySignature());
    }

    @Test
    @DisplayName("半配置判定：公钥与 APPID 恰好只配其一时为 true，两者都没配或都配齐为 false")
    void isRsa2ConfigurationIncompleteOnlyWhenExactlyOneIsConfigured() {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();

        // 只配公钥 → 半配置
        assertTrue(new AlipayGatewayClient(GATEWAY, "", privateKey(app), publicKey(alipay), NOTIFY_URL, "")
                .isRsa2ConfigurationIncomplete());
        // 只配 APPID → 半配置
        assertTrue(sandbox(privateKey(app), APP_ID).isRsa2ConfigurationIncomplete());
        // 都没配 → 不是半配置，调用方应走本地 HMAC 回退
        assertFalse(new AlipayGatewayClient(GATEWAY, "", "", "", "", "").isRsa2ConfigurationIncomplete());
        // 都配齐 → 正常
        assertFalse(gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, "").isRsa2ConfigurationIncomplete());
    }

    @Test
    @DisplayName("收银台链路齐全性：四项配置缺一即不算齐全，缺项以环境变量名列出且不含密钥原文")
    void reportsMissingCashierConfigurationByEnvName() {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();

        // 全配齐 → 无缺失，可以放行支付
        AlipayGatewayClient complete = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, "");
        assertTrue(complete.isCashierConfigurationComplete());
        assertTrue(complete.missingCashierConfiguration().isEmpty());

        // 缺支付宝公钥：链接签得出来、用户也付得了款，但回调验不了签 ⇒ 必须算【不齐】
        AlipayGatewayClient noPublicKey = gateway(privateKey(app), "", NOTIFY_URL, "");
        assertFalse(noPublicKey.isCashierConfigurationComplete());
        assertEquals(List.of("ALIPAY_PUBLIC_KEY"), noPublicKey.missingCashierConfiguration());

        // 缺回调地址：即便公钥齐备，支付宝也无处回传结果 ⇒ 同样算【不齐】
        assertEquals(List.of("ALIPAY_NOTIFY_URL"),
                new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), publicKey(alipay), "", "")
                        .missingCashierConfiguration());

        // 一项都没配：五个都缺，顺序固定（日志与接口提示都靠它稳定）
        assertEquals(List.of("ALIPAY_GATEWAY_URL", "ALIPAY_APP_ID", "ALIPAY_APP_PRIVATE_KEY",
                        "ALIPAY_PUBLIC_KEY", "ALIPAY_NOTIFY_URL"),
                new AlipayGatewayClient("", "", "", "", "", "").missingCashierConfiguration());

        // 缺项提示只输出变量名，绝不能夹带密钥原文（它会直接进接口 message 与日志）
        String brief = String.join("、",
                new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), "", "", "")
                        .missingCashierConfiguration());
        assertFalse(brief.contains(privateKey(app)), "缺项提示泄露了应用私钥");
        assertFalse(brief.contains(publicKey(alipay)), "缺项提示泄露了支付宝公钥");
    }

    @Test
    @DisplayName("「SDK 签得出来」不等于「可以放行支付」：缺公钥/回调地址时前者为 true、后者必须为 false")
    void signingAbilityIsWeakerThanCashierReadiness() {
        KeyPair app = keyPair();

        // 两个谓词的粒度差异就是本次评审指出的问题所在，这里把它钉死，防止后人用错判据。
        AlipayGatewayClient signableButNotReady = gateway(privateKey(app), "", NOTIFY_URL, "");
        assertTrue(signableButNotReady.canBuildCashierUrl(),
                "只看 SDK 三要素，它是能签出请求的（这也正是旧实现会产出可付款链接的原因）");
        assertFalse(signableButNotReady.isCashierConfigurationComplete(),
                "缺支付宝公钥 ⇒ 回调验不了签，绝不允许把链接交给用户");

        AlipayGatewayClient missingNotifyUrl =
                new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), publicKey(keyPair()), "", "");
        assertTrue(missingNotifyUrl.canBuildCashierUrl());
        assertFalse(missingNotifyUrl.isCashierConfigurationComplete(),
                "缺回调地址 ⇒ 支付宝无处回传结果，同样不许放行");
    }

    @Test
    @DisplayName("hasNotifyUrl 如实反映配置，describeConfiguration 只输出“已配置/未配置”且不含密钥原文")
    void describesConfigurationWithoutLeakingKeys() {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();

        assertTrue(gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID).hasNotifyUrl());
        assertFalse(new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), publicKey(alipay), "", "")
                .hasNotifyUrl());

        AlipayGatewayClient blank = new AlipayGatewayClient(GATEWAY, "", "", "", "", "");
        String description = blank.describeConfiguration();
        assertTrue(description.contains("appId=未配置"));
        assertTrue(description.contains("appPrivateKey=未配置"));
        assertTrue(description.contains("alipayPublicKey=未配置"));
        assertTrue(description.contains("notifyUrl=未配置"));
        assertTrue(description.contains("sellerId=未配置"));

        // 配了密钥时，自述里绝不能出现密钥原文
        AlipayGatewayClient configured = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID);
        String configuredDescription = configured.describeConfiguration();
        assertFalse(configuredDescription.contains(privateKey(app)), "自述泄露了应用私钥");
        assertFalse(configuredDescription.contains(publicKey(alipay)), "自述泄露了支付宝公钥");
        assertTrue(configuredDescription.contains("appPrivateKey=已配置"));
    }

    @Test
    @DisplayName("未配置 APPID/私钥时生成收银台地址直接拒绝，而不是抛 500 级别的未知异常")
    void throwsWhenCashierUrlNotConfigured() {
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, "", "", "", "", "");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.buildCashierUrl("TA1", new BigDecimal("1.00"), "订单"));
        assertTrue(ex.getMessage().contains("未配置"), "异常消息要能定位到是配置缺失：" + ex.getMessage());
    }

    // ------------------------------------------------------------------ 收银台链接（请求签名口径）

    @Test
    @DisplayName("收银台链接由官方 SDK 按请求口径签名（sign_type 参与），可用 rsaCheckV2 验回、rsaCheckV1 验不回")
    void cashierUrlIsSignedWithRequestCaliber() throws Exception {
        KeyPair app = keyPair();
        String url = sandbox(privateKey(app), APP_ID)
                .buildCashierUrl("TA20270301000001ABCD1234", new BigDecimal("1999.9"),
                        "旅行社团购订单 TA20270301000001ABCD1234");

        assertTrue(url.startsWith(GATEWAY + "?"), "收银台链接必须指向沙箱网关");

        Map<String, String> params = parseQuery(url);
        assertEquals("alipay.trade.page.pay", params.get("method"));
        assertEquals(APP_ID, params.get("app_id"));
        assertEquals("RSA2", params.get("sign_type"));
        assertEquals("json", params.get("format"));
        assertEquals("utf-8", params.get("charset"));
        assertEquals("1.0", params.get("version"));
        assertTrue(params.containsKey("timestamp"), "必须带支付宝时间戳");
        assertTrue(url.contains("FAST_INSTANT_TRADE_PAY"), "产品码必须出现在 biz_content 中");

        // 官方请求签名口径：只剔 sign，sign_type 参与
        assertTrue(AlipaySignature.rsaCheckV2(new LinkedHashMap<>(params), publicKey(app), "utf-8", "RSA2"),
                "收银台链接必须能用应用公钥按官方请求口径验回");

        // 反向对照：若实现误用了通知口径（把 sign_type 也剔掉）签，V2 就会失败，这里会先红。
        // 同时钉住「两套口径不可混用」这一契约，防止后人把通知的 rsaCheckV1 拿来验请求。
        assertFalse(AlipaySignature.rsaCheckV1(new LinkedHashMap<>(params), publicKey(app), "utf-8", "RSA2"),
                "收银台链接按请求口径签名（sign_type 参与），用通知口径必然对不上");
    }

    @Test
    @DisplayName("收银台链接带 notify_url，且它是被签进报文的公共参数")
    void cashierUrlCarriesSignedNotifyUrl() throws Exception {
        KeyPair app = keyPair();
        String url = gateway(privateKey(app), "", NOTIFY_URL, "")
                .buildCashierUrl("TA20270301000002ABCD1234", new BigDecimal("1.00"), "订单");

        Map<String, String> params = parseQuery(url);
        assertEquals(NOTIFY_URL, params.get("notify_url"), "付款结果必须能回传到本系统的回调地址");

        // 去掉 notify_url 后签名必须失配 —— 证明它不是「挂在 URL 上但没被签」的裸参数
        Map<String, String> stripped = new LinkedHashMap<>(params);
        stripped.remove("notify_url");
        assertFalse(AlipaySignature.rsaCheckV2(stripped, publicKey(app), "utf-8", "RSA2"),
                "notify_url 未参与签名，可被中间人改写");
    }

    @Test
    @DisplayName("未配置 ALIPAY_NOTIFY_URL 时收银台链接仍可生成，但确实不带 notify_url")
    void cashierUrlOmitsNotifyUrlWhenNotConfigured() {
        KeyPair app = keyPair();
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), "", "", "");

        assertFalse(client.hasNotifyUrl());
        String url = client.buildCashierUrl("TA20270301000003ABCD1234", new BigDecimal("10.00"), "订单");

        assertTrue(url.startsWith(GATEWAY + "?"), "缺 notify_url 不应该让收银台链接生成失败");
        assertFalse(parseQuery(url).containsKey("notify_url"), "未配置时不得凭空出现 notify_url");
    }

    @Test
    @DisplayName("金额统一格式化为两位小数，商品名中的引号与反斜杠被转义")
    void formatsAmountAndEscapesSubject() {
        KeyPair app = keyPair();

        Map<String, String> params = parseQuery(sandbox(privateKey(app), APP_ID)
                .buildCashierUrl("TA1", new BigDecimal("1000"), "含\"引号\"的订单名"));

        String biz = params.get("biz_content");
        assertTrue(biz.contains("\"total_amount\":\"1000.00\""), "金额必须是两位小数字符串：" + biz);
        assertTrue(biz.contains("\\\"引号\\\""), "商品名中的引号必须转义：" + biz);
        assertTrue(biz.contains("\"out_trade_no\":\"TA1\""));
    }

    @Test
    @DisplayName("biz_content：金额四舍五入到两位小数、反斜杠与引号转义、subject 为空时不产出 null")
    void bizContentFollowsContract() {
        String biz = AlipayGatewayClient.bizContent("TA1", new BigDecimal("1999.999"), "a\\b\"c");

        assertTrue(biz.contains("\"total_amount\":\"2000.00\""), "必须按 HALF_UP 取两位小数：" + biz);
        assertTrue(biz.contains("\"subject\":\"a\\\\b\\\"c\""), "反斜杠与引号都要转义：" + biz);
        assertTrue(biz.contains("\"product_code\":\"FAST_INSTANT_TRADE_PAY\""));

        // null subject 不能拼出字面量 null 或 NPE
        String nullSubject = AlipayGatewayClient.bizContent("TA1", new BigDecimal("1"), null);
        assertTrue(nullSubject.contains("\"subject\":\"\""), "subject 为空时应是空串：" + nullSubject);
    }

    @Test
    @DisplayName("时间戳用支付宝口径（+08:00），格式为 yyyy-MM-dd HH:mm:ss")
    void timestampUsesAlipayZone() {
        KeyPair app = keyPair();

        Map<String, String> params = parseQuery(sandbox(privateKey(app), APP_ID)
                .buildCashierUrl("TA1", new BigDecimal("1.00"), "订单"));

        String timestamp = params.get("timestamp");
        LocalDateTime parsed = LocalDateTime.parse(timestamp, ALIPAY_TIME);

        // 实测 SDK 的时间戳固定按 +08:00 产出，不受 JVM 默认时区影响（-Duser.timezone=UTC 下同样如此），
        // 因此这里可以断言时区，而不是只断言格式。
        LocalDateTime beijingNow = OffsetDateTime.now(ZoneOffset.ofHours(8)).toLocalDateTime();
        long skewSeconds = Math.abs(Duration.between(beijingNow, parsed).getSeconds());
        assertTrue(skewSeconds <= 30, "时间戳必须接近北京时间：" + timestamp + "（偏差 " + skewSeconds + " 秒）");
    }

    // ------------------------------------------------------------------ 异步通知验签（官方 SDK 口径）

    @Test
    @DisplayName("真实支付宝通知（官方 V1 口径签名）可以通过验签")
    void verifiesGenuineNotify() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();

        assertTrue(gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID)
                .verifyNotifySignature(signedNotify(APP_ID, SELLER_ID, alipay)));
    }

    @Test
    @DisplayName("同一份参数可重复验签，且官方 SDK 的删 key 副作用不会波及调用方的 Map")
    void notifyVerificationIsRepeatableAndDoesNotMutateCallerMap() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID);

        Map<String, String> notify = signedNotify(APP_ID, SELLER_ID, alipay);

        assertTrue(client.verifyNotifySignature(notify));
        // 实测官方 getSignCheckContentV1 会无条件删掉传入 Map 的 sign/sign_type；实现传了副本，
        // 所以调用方的 Map 必须原样保留，否则「验一次就被掏空」会污染支付回调的后续处理。
        assertTrue(notify.containsKey("sign"), "调用方 Map 里的 sign 被 SDK 删掉了，说明实现没有传副本");
        assertTrue(notify.containsKey("sign_type"), "调用方 Map 里的 sign_type 被 SDK 删掉了");
        assertEquals("RSA2", notify.get("sign_type"));

        // 同一份参数（同一个 Map 对象）再验一次仍须通过
        assertTrue(client.verifyNotifySignature(notify), "同一份参数第二次验签失败了，说明副作用没有隔离");
    }

    @Test
    @DisplayName("通知被篡改金额后验签不通过")
    void rejectsTamperedNotify() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID);

        Map<String, String> params = signedNotify(APP_ID, SELLER_ID, alipay);
        params.put("total_amount", "0.01");   // 签名覆盖金额，改动必然失配

        assertFalse(client.verifyNotifySignature(params));
    }

    @Test
    @DisplayName("缺少 sign、空参数表一律拒绝，且不抛异常")
    void rejectsNotifyWithoutSign() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID);

        // 有报文但没有 sign
        Map<String, String> noSign = notifyParams(APP_ID, SELLER_ID);
        assertFalse(client.verifyNotifySignature(noSign));
        // sign 存在但为空串
        Map<String, String> blankSign = notifyParams(APP_ID, SELLER_ID);
        blankSign.put("sign", "   ");
        assertFalse(client.verifyNotifySignature(blankSign));
        // null / 空表
        assertFalse(client.verifyNotifySignature(null));
        assertFalse(client.verifyNotifySignature(new LinkedHashMap<>()));
    }

    @Test
    @DisplayName("非法 Base64 的 sign 被拒绝：官方 SDK 会抛异常，必须被实现吞掉而不是打成 5xx")
    void rejectsMalformedSignatureWithoutThrowing() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID);

        Map<String, String> params = notifyParams(APP_ID, SELLER_ID);
        params.put("sign", "!!!not-base64!!!");

        // 实测该场景 rsaCheckV1 抛 AlipayApiException 而非返回 false
        assertFalse(client.verifyNotifySignature(params),
                "非法 sign 必须是「拒绝」而不是异常穿到 controller");
    }

    @Test
    @DisplayName("用别人的私钥签的通知被支付宝公钥拒绝（防止其他应用串单）")
    void rejectsNotifySignedByForeignKey() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        KeyPair attacker = keyPair();
        AlipayGatewayClient client = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID);

        assertFalse(client.verifyNotifySignature(signedNotify(APP_ID, SELLER_ID, attacker)));
    }

    @Test
    @DisplayName("通知声明的 app_id 与本应用不一致时拒绝，即使签名本身合法")
    void rejectsNotifyForOtherApp() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID);

        // 用支付宝私钥签的、完全合法的签名，但声明的是另一个应用
        Map<String, String> params = signedNotify(OTHER_APP_ID, SELLER_ID, alipay);

        assertFalse(client.verifyNotifySignature(params), "其他应用的合法通知不能被拿去串单");
    }

    @Test
    @DisplayName("配置了 seller_id 时，通知里的 seller_id 不匹配（含缺失）一律拒绝")
    void rejectsNotifyForOtherSellerWhenSellerConfigured() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, SELLER_ID);

        assertFalse(client.verifyNotifySignature(signedNotify(APP_ID, OTHER_SELLER_ID, alipay)),
                "seller_id 不匹配必须拒绝");

        Map<String, String> missingSeller = notifyParams(APP_ID, "");
        missingSeller.remove("seller_id");
        missingSeller.put("sign", sign(missingSeller, alipay));
        assertFalse(client.verifyNotifySignature(missingSeller),
                "配置了 seller_id 却没有收到 seller_id，属于信息不全，同样拒绝");
    }

    @Test
    @DisplayName("未配置 seller_id 时不因 seller_id 拒绝（该字段不是契约必填）")
    void acceptsAnySellerWhenSellerNotConfigured() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = gateway(privateKey(app), publicKey(alipay), NOTIFY_URL, "");

        assertTrue(client.verifyNotifySignature(signedNotify(APP_ID, OTHER_SELLER_ID, alipay)));
    }

    // ------------------------------------------------------------------ fail-closed：半配置必须拒绝

    @Test
    @DisplayName("只配了公钥（缺 APPID）时验签一律失败，不会退化成放行")
    void failsClosedWhenAppIdMissing() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        // 公钥配了、APPID 没配 —— 正是「半配置」
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, "", privateKey(app), publicKey(alipay),
                NOTIFY_URL, SELLER_ID);

        assertFalse(client.verifyNotifySignature(signedNotify(APP_ID, SELLER_ID, alipay)));
    }

    @Test
    @DisplayName("只配了 APPID（缺公钥）时验签一律失败，不会退化成放行")
    void failsClosedWhenPublicKeyMissing() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), "",
                NOTIFY_URL, SELLER_ID);

        assertFalse(client.verifyNotifySignature(signedNotify(APP_ID, SELLER_ID, alipay)));

        // 连公钥都是空串，SDK 会抛异常，实现必须照样给出「拒绝」
        Map<String, String> params = notifyParams(APP_ID, SELLER_ID);
        params.put("sign", sign(params, alipay));
        assertFalse(client.verifyNotifySignature(params));
    }

    // ------------------------------------------------------------------ 密钥形态（PEM）

    @Test
    @DisplayName("密钥归一化：剥掉 PEM 头尾与所有空白，单行/多行 PEM 与纯 Base64 等价")
    void normalizeKeyStripsPemArmor() {
        String body = privateKey(keyPair());

        // 已经是纯 Base64：原样返回（幂等）
        assertEquals(body, AlipayGatewayClient.normalizeKey(body));
        // 单行 PEM（头尾紧贴主体）
        assertEquals(body, AlipayGatewayClient.normalizeKey(
                "-----BEGIN PRIVATE KEY-----" + body + "-----END PRIVATE KEY-----"));
        // 多行 PEM（OpenSSL 常见的每行 64 字符折行 + \n）
        assertEquals(body, AlipayGatewayClient.normalizeKey(pem("PRIVATE KEY", body)));
        // 前后还有多余空白
        assertEquals(body, AlipayGatewayClient.normalizeKey("  " + pem("PUBLIC KEY", body) + "  \n"));
        // null 不能 NPE
        assertNull(AlipayGatewayClient.normalizeKey(null));
    }

    @Test
    @DisplayName("带 PEM 头尾的密钥可直接使用：能签出收银台链接、也能验签通知")
    void acceptsPemWrappedKeys() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();

        // 应用私钥与支付宝公钥都按 PEM 形态给（支付宝控制台/OpenSSL 复制出来的样子）
        AlipayGatewayClient client = new AlipayGatewayClient(
                GATEWAY, APP_ID,
                pem("PRIVATE KEY", privateKey(app)),
                pem("PUBLIC KEY", publicKey(alipay)),
                NOTIFY_URL, SELLER_ID);

        assertTrue(client.canBuildCashierUrl(), "PEM 形态的应用私钥应当可用");
        assertTrue(client.canVerifyNotifySignature(), "PEM 形态的支付宝公钥应当可用");
        assertFalse(client.isRsa2ConfigurationIncomplete(), "都是已配置，不该判成半配置");

        // 收银台链接确实签了出来，且能用【纯 Base64】形态的应用公钥验回
        // —— 若 PEM 私钥没被正确剥壳，这里的签名必然对不上。
        Map<String, String> params = parseQuery(client.buildCashierUrl(
                "TA20270301000009ABCD1234", new BigDecimal("1.00"), "订单"));
        assertTrue(AlipaySignature.rsaCheckV2(new LinkedHashMap<>(params), publicKey(app), "utf-8", "RSA2"),
                "PEM 形态的应用私钥没有被正确归一化，收银台签名对不上");

        // 通知验签同样要真的按 PEM 公钥生效，而不是「恰好放行」
        assertTrue(client.verifyNotifySignature(signedNotify(APP_ID, SELLER_ID, alipay)),
                "PEM 形态的支付宝公钥没有被正确归一化，验签失败");
        // 反向：换一对密钥签的通知仍必须被拒（证明上面的 true 是真验签，不是无条件放行）
        assertFalse(client.verifyNotifySignature(signedNotify(APP_ID, SELLER_ID, keyPair())),
                "PEM 公钥路径下也不能放行他人签名的通知");
    }

    // ------------------------------------------------------------------ 退款出款

    @Test
    @DisplayName("退款出款只要求「签得出来」的三项：网关 + APPID + 应用私钥，不要求公钥与回调地址")
    void refundConfigurationNeedsOnlySigningPrerequisites() {
        KeyPair app = keyPair();

        // 三要素齐备即可出款：退款是同步接口，不需要回调地址，也不需要验签别人的报文
        assertTrue(sandbox(privateKey(app), APP_ID).isRefundConfigurationComplete());
        assertTrue(sandbox(privateKey(app), APP_ID).missingRefundConfiguration().isEmpty());
        // 与收银台的差别正在这里：公钥 / 回调地址缺失不影响出款
        assertFalse(sandbox(privateKey(app), APP_ID).isCashierConfigurationComplete());

        // 缺任一要素都不行
        assertFalse(sandbox("", APP_ID).isRefundConfigurationComplete());
        assertFalse(sandbox(privateKey(app), "").isRefundConfigurationComplete());
        assertFalse(new AlipayGatewayClient("", APP_ID, privateKey(app), "", "", "")
                .isRefundConfigurationComplete());

        // 缺失项以环境变量名给出，顺序固定，便于运维按名补齐；且不含密钥内容
        assertEquals(List.of("ALIPAY_APP_ID", "ALIPAY_APP_PRIVATE_KEY"),
                sandbox("", "").missingRefundConfiguration());
    }

    @Test
    @DisplayName("未配置时不允许发起退款，直接抛 IllegalStateException 而不产生请求")
    void refundFailsFastWhenNotConfigured() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sandbox("", "").refund("TA20270301000001ABCD1234", "RF1", new BigDecimal("2999.00"), "行程有变"));

        assertTrue(ex.getMessage().contains("无法发起退款"));
    }

    @Test
    @DisplayName("退款请求体带 out_request_no 幂等键，金额固定两位小数")
    void refundBizContentCarriesIdempotencyKeyAndNormalizedAmount() {
        String biz = AlipayGatewayClient.refundBizContent(
                "TA20270301000001ABCD1234", "RF70", new BigDecimal("2999"), "行程有变，退全款");

        // 逐字比对，避免以后有人改名成 out_request_id / refund_money 之类支付宝不认的字段
        assertEquals("{\"out_trade_no\":\"TA20270301000001ABCD1234\""
                + ",\"refund_amount\":\"2999.00\""
                + ",\"out_request_no\":\"RF70\""
                + ",\"refund_reason\":\"行程有变，退全款\"}", biz);
    }

    @Test
    @DisplayName("⚠️ 退款成功判定必须看 code + fund_change，不能看 isSuccess()：空响应与 code=null 都会被 isSuccess() 判成 true")
    void refundOutcomeIsJudgedByCodeNotByIsSuccess() {
        // 坑的实证：SDK 的 isSuccess() 判据是「code 不是 40004/20000」，
        // 所以一个全新、什么都没填的响应对象也会返回 true。用它判退款＝把「支付宝没回话」当「钱已退」。
        AlipayTradeRefundResponse blank = new AlipayTradeRefundResponse();
        assertTrue(blank.isSuccess(), "这是 SDK 的既有行为，本用例把它钉住，防止有人误用");
        assertFalse(AlipayGatewayClient.fromResponse(blank).success(),
                "空白响应绝不能被当作出款成功（fail-closed）");
        assertEquals(AlipayGatewayClient.RefundOutcome.NEEDS_CONFIRMATION,
                AlipayGatewayClient.classify(blank), "连 code 都没有，只能去查询确认");

        AlipayTradeRefundResponse nullCode = new AlipayTradeRefundResponse();
        nullCode.setMsg("Success");
        assertFalse(AlipayGatewayClient.fromResponse(nullCode).success());
        assertEquals(AlipayGatewayClient.RefundOutcome.NEEDS_CONFIRMATION,
                AlipayGatewayClient.classify(nullCode), "code 缺失时不能就地判定");

        // 确定成功：code=10000 且 fund_change=Y
        AlipayTradeRefundResponse ok = new AlipayTradeRefundResponse();
        ok.setCode("10000");
        ok.setFundChange("Y");
        ok.setTradeNo("2027030122001400000000000001");
        ok.setOutTradeNo("TA20270301000001ABCD1234");
        assertEquals(AlipayGatewayClient.RefundOutcome.CONFIRMED_SUCCESS,
                AlipayGatewayClient.classify(ok));
        AlipayGatewayClient.RefundResult settled = AlipayGatewayClient.fromResponse(ok);
        assertTrue(settled.success());
        assertEquals("2027030122001400000000000001", settled.tradeNo());
        assertEquals("TA20270301000001ABCD1234", settled.outTradeNo());

        // 业务失败：错误码优先取 sub_code（更可定位），并带上可读原因
        AlipayTradeRefundResponse rejected = new AlipayTradeRefundResponse();
        rejected.setCode("40004");
        rejected.setMsg("Business Failed");
        rejected.setSubCode("ACQ.TRADE_NOT_EXIST");
        rejected.setSubMsg("交易不存在");
        assertEquals(AlipayGatewayClient.RefundOutcome.REJECTED,
                AlipayGatewayClient.classify(rejected), "回了非空非 10000 的 code ⇒ 明确被拒");
        AlipayGatewayClient.RefundResult failed = AlipayGatewayClient.fromResponse(rejected);
        assertFalse(failed.success());
        assertEquals("ACQ.TRADE_NOT_EXIST", failed.code());
        assertEquals("交易不存在", failed.message());

        // 连响应对象都没有
        assertFalse(AlipayGatewayClient.fromResponse(null).success());
        assertEquals(AlipayGatewayClient.RefundOutcome.NEEDS_CONFIRMATION,
                AlipayGatewayClient.classify(null));
    }

    @Test
    @DisplayName("⚠️ 评审核心：code=10000 但 fund_change=N 或【字段缺失】都不算成功，必须查询确认")
    void refundNeverTreatsMissingFundChangeAsSuccess() {
        // 官方口径：code=10000 只代表「请求处理成功」，不代表钱动了。
        // fund_change=N（钱没动，例如同一请求号被重复提交）与字段缺失都必须用原 out_request_no
        // 查询退款结果后再下结论 —— 既不能当成功（会虚报已退款），也不能当失败（首次可能其实成功了）。
        AlipayTradeRefundResponse fundChangeNo = new AlipayTradeRefundResponse();
        fundChangeNo.setCode("10000");
        fundChangeNo.setFundChange("N");
        fundChangeNo.setTradeNo("2027030122001400000000000001");

        AlipayTradeRefundResponse fundChangeMissing = new AlipayTradeRefundResponse();
        fundChangeMissing.setCode("10000");
        fundChangeMissing.setTradeNo("2027030122001400000000000001");

        for (AlipayTradeRefundResponse response : List.of(fundChangeNo, fundChangeMissing)) {
            assertEquals(AlipayGatewayClient.RefundOutcome.NEEDS_CONFIRMATION,
                    AlipayGatewayClient.classify(response),
                    "code=10000 且 fund_change=" + response.getFundChange() + " 不能就地判定成败");

            AlipayGatewayClient.RefundResult result = AlipayGatewayClient.fromResponse(response);
            assertFalse(result.success(), "未经查询确认的退款响应绝不能被当作成功");
            assertEquals("REFUND_RESULT_UNCONFIRMED", result.code());
        }
    }

    @Test
    @DisplayName("fund_change=Y 已经是确定结论，不必再多查一次")
    void refundSkipsQueryWhenFundChangeIsYes() throws Exception {
        AlipayClient sdk = mock(AlipayClient.class);
        AlipayTradeRefundResponse refundResponse = new AlipayTradeRefundResponse();
        refundResponse.setCode("10000");
        refundResponse.setFundChange("Y");
        refundResponse.setTradeNo("2027030122001400000000000001");

        doAnswer(invocation -> refundResponse).when(sdk).execute(any(AlipayTradeRefundRequest.class));

        AlipayGatewayClient client = spy(sandbox(privateKey(keyPair()), APP_ID));
        doReturn(sdk).when(client).alipayClient();

        AlipayGatewayClient.RefundResult result =
                client.refund("TA20270301000001ABCD1234", "RF70", new BigDecimal("2999.00"), "行程有变");

        assertTrue(result.success());
        verify(sdk, never()).execute(any(AlipayTradeFastpayRefundQueryRequest.class));
    }

    @Test
    @DisplayName("退款响应 fund_change=N 时按【原请求号】查询，查到 REFUND_SUCCESS 才判成功")
    void refundConfirmsByQueryWhenFundChangeIsNo() throws Exception {
        AlipayClient sdk = mock(AlipayClient.class);
        AlipayTradeRefundResponse refundResponse = new AlipayTradeRefundResponse();
        refundResponse.setCode("10000");
        refundResponse.setFundChange("N");

        AlipayTradeFastpayRefundQueryResponse queryResponse = new AlipayTradeFastpayRefundQueryResponse();
        queryResponse.setCode("10000");
        queryResponse.setRefundStatus("REFUND_SUCCESS");
        queryResponse.setTradeNo("2027030122001400000000000001");
        queryResponse.setOutTradeNo("TA20270301000001ABCD1234");

        List<Object> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return refundResponse;
        }).when(sdk).execute(any(AlipayTradeRefundRequest.class));
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return queryResponse;
        }).when(sdk).execute(any(AlipayTradeFastpayRefundQueryRequest.class));

        AlipayGatewayClient client = spy(sandbox(privateKey(keyPair()), APP_ID));
        doReturn(sdk).when(client).alipayClient();

        AlipayGatewayClient.RefundResult result =
                client.refund("TA20270301000001ABCD1234", "RF70", new BigDecimal("2999.00"), "行程有变");

        assertTrue(result.success(), "查询确认退款成功后应判成功");
        assertEquals("2027030122001400000000000001", result.tradeNo());

        // 查询必须落在同一笔退款上：换请求号查到的是另一笔，结论对本笔无效
        assertEquals(2, sent.size(), "应依次发出「退款」与「退款查询」两个请求");
        assertTrue(sent.get(1) instanceof AlipayTradeFastpayRefundQueryRequest,
                "第二次调用的必须是退款查询，实际是 " + sent.get(1).getClass().getName());
        String queryBiz = ((AlipayTradeFastpayRefundQueryRequest) sent.get(1)).getBizContent();
        assertTrue(queryBiz.contains("\"out_request_no\":\"RF70\""),
                "查询必须带原退款请求号：" + queryBiz);
        assertTrue(queryBiz.contains("\"out_trade_no\":\"TA20270301000001ABCD1234\""),
                "查询必须带原商户订单号：" + queryBiz);
    }

    @Test
    @DisplayName("退款请求超时（AlipayApiException）不直接判失败，而是先查询确认——首次可能其实已出款")
    void refundConfirmsByQueryWhenRequestTimesOut() throws Exception {
        AlipayClient sdk = mock(AlipayClient.class);
        AlipayTradeFastpayRefundQueryResponse queryResponse = new AlipayTradeFastpayRefundQueryResponse();
        queryResponse.setCode("10000");
        queryResponse.setRefundStatus("REFUND_SUCCESS");
        queryResponse.setTradeNo("2027030122001400000000000001");

        List<Object> sent = new ArrayList<>();
        doAnswer(invocation -> {
            throw new AlipayApiException("Read timed out");
        }).when(sdk).execute(any(AlipayTradeRefundRequest.class));
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return queryResponse;
        }).when(sdk).execute(any(AlipayTradeFastpayRefundQueryRequest.class));

        AlipayGatewayClient client = spy(sandbox(privateKey(keyPair()), APP_ID));
        doReturn(sdk).when(client).alipayClient();

        AlipayGatewayClient.RefundResult result =
                client.refund("TA20270301000001ABCD1234", "RF70", new BigDecimal("2999.00"), "行程有变");

        assertTrue(result.success(),
                "超时后查询到已退款成功，必须判成功（否则本地会永远停在待审核）");
        assertEquals(1, sent.size(), "超时路径必须发起一次退款查询");
    }

    @Test
    @DisplayName("查询也没能确认时不落已退款：返回 unconfirmed，请求号恒定所以可原样重试")
    void refundStaysUnconfirmedWhenQueryCannotConfirm() throws Exception {
        AlipayClient sdk = mock(AlipayClient.class);
        AlipayTradeRefundResponse refundResponse = new AlipayTradeRefundResponse();
        refundResponse.setCode("10000");
        refundResponse.setFundChange("N");

        doAnswer(invocation -> refundResponse).when(sdk).execute(any(AlipayTradeRefundRequest.class));
        doAnswer(invocation -> {
            throw new AlipayApiException("connect timed out");
        }).when(sdk).execute(any(AlipayTradeFastpayRefundQueryRequest.class));

        AlipayGatewayClient client = spy(sandbox(privateKey(keyPair()), APP_ID));
        doReturn(sdk).when(client).alipayClient();

        AlipayGatewayClient.RefundResult result =
                client.refund("TA20270301000001ABCD1234", "RF70", new BigDecimal("2999.00"), "行程有变");

        assertFalse(result.success(), "连查询都失败了，绝不能判成功");
        assertEquals("REFUND_RESULT_UNCONFIRMED", result.code());
        assertTrue(result.message().contains("查询"),
                "原因里要说清查询也失败了，便于后台判断是重试还是查人工：" + result.message());
    }

    @Test
    @DisplayName("退款查询必须同时看 code 与 refund_status，光 code=10000 不算查到成功")
    void refundQueryRequiresRefundStatusSuccess() {
        AlipayTradeFastpayRefundQueryResponse processing = new AlipayTradeFastpayRefundQueryResponse();
        processing.setCode("10000");
        processing.setRefundStatus("REFUND_PROCESSING");
        assertFalse(AlipayGatewayClient.isRefundQuerySucceeded(processing),
                "退款处理中不等于退款成功");

        AlipayTradeFastpayRefundQueryResponse blank = new AlipayTradeFastpayRefundQueryResponse();
        assertFalse(AlipayGatewayClient.isRefundQuerySucceeded(blank));
        assertFalse(AlipayGatewayClient.isRefundQuerySucceeded(null));

        AlipayTradeFastpayRefundQueryResponse succeeded = new AlipayTradeFastpayRefundQueryResponse();
        succeeded.setCode("10000");
        succeeded.setRefundStatus("REFUND_SUCCESS");
        assertTrue(AlipayGatewayClient.isRefundQuerySucceeded(succeeded));
    }

    @Test
    @DisplayName("「结果未确认」与「明确失败」可由 RefundResult 直接区分，且两处结果码逐字一致")
    void unconfirmedResultIsDistinguishableFromExplicitFailure() {
        AlipayGatewayClient.RefundResult pending =
                AlipayGatewayClient.RefundResult.unconfirmed("请求超时且查询无结论");
        AlipayGatewayClient.RefundResult rejected =
                AlipayGatewayClient.RefundResult.failed(null, "ACQ.TRADE_NOT_EXIST", "交易不存在");
        AlipayGatewayClient.RefundResult settled = AlipayGatewayClient.RefundResult.succeeded(
                "2027030122001400000000000001", "TA20270301000001ABCD1234");

        // 两者的 success() 都是 false，但只有「未确认」意味着钱可能已经退出去 ——
        // 调用方必须据此禁止拒绝退款，而不是把它当普通失败退回待审核。
        assertFalse(pending.success());
        assertFalse(rejected.success());
        assertTrue(pending.unconfirmed(), "未确认必须能被识别出来");
        assertFalse(rejected.unconfirmed(), "明确失败不是未确认：钱确定没动，可以重试也可以拒绝");
        assertFalse(settled.unconfirmed());
        // 异常码与判定码是两处独立定义，必须逐字一致 ——
        // 否则同一个场景会对外返回两种错误码，前端与运维都得同时认两套。
        assertEquals(RefundPendingConfirmationException.CODE,
                AlipayGatewayClient.RefundResult.UNCONFIRMED_CODE);
    }

    @Test
    @DisplayName("退款原因含换行/制表符时生成的仍是合法 JSON（控制字符必须转义）")
    void refundBizContentEscapesControlCharactersInReason() {
        String biz = AlipayGatewayClient.refundBizContent(
                "TA20270301000001ABCD1234", "RF70", new BigDecimal("2999"),
                "第一行\n第二行\t带制表符\r\n结束");

        // 逐字断言：换行必须是 \n、制表符必须是 \t、回车必须是 \r。
        // 旧实现用手工拼串 + 只转义引号/反斜杠，会把裸控制字符留在报文里，
        // 支付宝侧解析 JSON 立刻失败 —— 用户在备注里敲个回车就能让退款发不出去。
        assertEquals("{\"out_trade_no\":\"TA20270301000001ABCD1234\""
                + ",\"refund_amount\":\"2999.00\""
                + ",\"out_request_no\":\"RF70\""
                + ",\"refund_reason\":\"第一行\\n第二行\\t带制表符\\r\\n结束\"}", biz);

        // 反向对照：报文里不能再出现裸露的换行 / 制表符 / 回车
        assertFalse(biz.contains("\n"), "refund_reason 里的换行没有被转义：" + biz);
        assertFalse(biz.contains("\t"), "refund_reason 里的制表符没有被转义：" + biz);
        assertFalse(biz.contains("\r"), "refund_reason 里的回车没有被转义：" + biz);
    }

    // ------------------------------------------------------------------ helpers

    /** 沙箱客户端：只关心收银台链接的用例用（不配支付宝公钥）。 */
    private static AlipayGatewayClient sandbox(String appPrivateKey, String appId) {
        return new AlipayGatewayClient(GATEWAY, appId, appPrivateKey, "", "", "");
    }

    /** 全量构造，按用例需要给公钥 / notify_url / seller_id。 */
    private static AlipayGatewayClient gateway(String appPrivateKey, String alipayPublicKey,
                                              String notifyUrl, String sellerId) {
        return new AlipayGatewayClient(GATEWAY, APP_ID, appPrivateKey, alipayPublicKey, notifyUrl, sellerId);
    }

    /** 构造一份「支付宝侧已按官方 V1 口径签名」的异步通知报文。 */
    private static Map<String, String> signedNotify(String appId, String sellerId, KeyPair alipay)
            throws Exception {
        Map<String, String> params = notifyParams(appId, sellerId);
        params.put("sign", sign(params, alipay));
        return params;
    }

    private static Map<String, String> notifyParams(String appId, String sellerId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("gmt_create", "2027-03-01 10:00:00");
        params.put("charset", "utf-8");
        params.put("seller_email", "sandbox@alipaydev.com");
        params.put("subject", "旅行社团购订单");
        params.put("buyer_id", "2088722000000000");
        params.put("notify_id", "2027030100222100000000000000");
        params.put("notify_type", "trade_status_sync");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "1999.90");
        params.put("trade_no", "2027030122001400000000000001");
        params.put("app_id", appId);
        params.put("seller_id", sellerId);
        params.put("notify_time", "2027-03-01 10:00:05");
        params.put("out_trade_no", "TA20270301000001ABCD1234");
        params.put("sign_type", "RSA2");           // 官方 V1 口径下必须剔除
        return params;
    }

    /**
     * 按官方 {@code AlipaySignature.rsaSign} + {@code getSignCheckContentV1} 对通知签名。
     *
     * <p>这里刻意不自己拼串：签名口径必须与 {@code rsaCheckV1} 完全同源，否则用例本身
     * 就成了「自证自话」。同源之后，实现只要偏离官方口径就会红。</p>
     */
    private static String sign(Map<String, String> params, KeyPair alipay) throws Exception {
        return AlipaySignature.rsaSign(
                AlipaySignature.getSignCheckContentV1(new LinkedHashMap<>(params)),
                base64(alipay.getPrivate().getEncoded()), "utf-8", "RSA2");
    }

    private static Map<String, String> parseQuery(String url) {
        String query = url.substring(url.indexOf('?') + 1);
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split < 0) {
                continue;
            }
            params.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    /** 生成带 PEM 头尾的密钥片段，按每行 64 字符折行（OpenSSL/keytool 导出的常见形态）。 */
    private static String pem(String label, String body) {
        StringBuilder pem = new StringBuilder("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < body.length(); i += 64) {
            pem.append(body, i, Math.min(i + 64, body.length())).append('\n');
        }
        return pem.append("-----END ").append(label).append("-----").toString();
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String privateKey(KeyPair keyPair) {
        return base64(keyPair.getPrivate().getEncoded());
    }

    private static String publicKey(KeyPair keyPair) {
        return base64(keyPair.getPublic().getEncoded());
    }

    private static String base64(byte[] der) {
        return Base64.getEncoder().encodeToString(der);
    }
}
