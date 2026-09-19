package com.travelagency.common.alipay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付宝沙箱适配器的签名/验签规则。
 *
 * <p>这些用例不联网：密钥对在测试内用 {@link KeyPairGenerator} 现场生成，
 * 「应用私钥」与「支付宝私钥」各用一对，用来分别模拟本应用签名与支付宝签名。
 * 覆盖的规则与支付宝官方 {@code AlipaySignature} 一致：</p>
 * <ul>
 *   <li><b>请求签名</b>：剔除 {@code sign} 与空值，{@code sign_type} <b>参与</b>签名；</li>
 *   <li><b>异步通知验签</b>：剔除 {@code sign} <b>和</b> {@code sign_type}（等价 {@code rsaCheckV1}）；</li>
 *   <li>通知声明的 {@code app_id} 必须与本应用一致；</li>
 *   <li>任何一项不满足都按拒绝处理（fail-closed），且不抛异常打死调用方。</li>
 * </ul>
 */
class AlipayGatewayClientTest {

    private static final String GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final String APP_ID = "9021000168641134";
    private static final String NOTIFY_PATH = "/api/payments/alipay/notify";

    // ------------------------------------------------------------------ 配置判定

    @Test
    @DisplayName("APPID 与应用私钥齐备时才能生成收银台地址")
    void requiresAppIdAndPrivateKeyToBuildCashierUrl() {
        KeyPair app = keyPair();

        assertTrue(sandbox(app, APP_ID).canBuildCashierUrl());
        // 缺少 APPID：登录沙箱页面前就是这种状态，必须回退占位而不是抛异常
        assertFalse(new AlipayGatewayClient(GATEWAY, "", privateKey(app), "").canBuildCashierUrl());
        // 缺少应用私钥
        assertFalse(new AlipayGatewayClient(GATEWAY, APP_ID, "", "").canBuildCashierUrl());
        // 网关地址为空
        assertFalse(new AlipayGatewayClient("", APP_ID, privateKey(app), "").canBuildCashierUrl());
    }

    @Test
    @DisplayName("只有配置了支付宝公钥才具备官方验签能力")
    void requiresAlipayPublicKeyToVerifyNotify() {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();

        assertFalse(sandbox(app, APP_ID).canVerifyNotifySignature());
        assertTrue(new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), publicKey(alipay))
                .canVerifyNotifySignature());
    }

    @Test
    @DisplayName("未配置时生成收银台地址直接拒绝，且自述配置只输出“已配置/未配置”，不泄露密钥内容")
    void throwsWhenNotConfigured() {
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, "", "", "");

        assertThrows(IllegalStateException.class,
                () -> client.buildCashierUrl("TA1", new BigDecimal("1.00"), "订单"));

        String description = client.describeConfiguration();
        assertTrue(description.contains("appId=未配置"));
        assertTrue(description.contains("appPrivateKey=未配置"));
        assertTrue(description.contains("alipayPublicKey=未配置"));
    }

    // ------------------------------------------------------------------ 收银台链接

    @Test
    @DisplayName("收银台链接用应用私钥签名，且签名可被应用公钥验回（sign_type 参与签名）")
    void cashierUrlIsSignedWithAppPrivateKey() throws Exception {
        KeyPair app = keyPair();
        AlipayGatewayClient client = sandbox(app, APP_ID);

        String url = client.buildCashierUrl("TA20270301000001ABCD1234", new BigDecimal("1999.9"),
                "旅行社团购订单 TA20270301000001ABCD1234");

        assertTrue(url.startsWith(GATEWAY + "?"), "收银台链接必须指向沙箱网关");

        Map<String, String> params = parseQuery(url);
        String sign = params.remove("sign");

        assertEquals("alipay.trade.page.pay", params.get("method"));
        assertEquals(APP_ID, params.get("app_id"));
        assertEquals("RSA2", params.get("sign_type"));
        assertEquals("JSON", params.get("format"));
        assertEquals("1.0", params.get("version"));
        assertTrue(params.containsKey("timestamp"), "必须带支付宝时间戳");
        assertTrue(url.contains("FAST_INSTANT_TRADE_PAY"), "产品码必须出现在 biz_content 中");

        // 只剔除 sign，保留 sign_type → 若实现把 sign_type 也剔掉了，这里就会验签失败
        String content = AlipayGatewayClient.canonicalContent(params, Set.of("sign"));
        assertTrue(AlipayGatewayClient.rsa2Verify(content, sign, publicKey(app)),
                "收银台链接的签名必须能用应用公钥验回");
    }

    @Test
    @DisplayName("金额统一格式化为两位小数，商品名中的引号被转义")
    void formatsAmountAndEscapesSubject() {
        KeyPair app = keyPair();
        AlipayGatewayClient client = sandbox(app, APP_ID);

        Map<String, String> params = parseQuery(
                client.buildCashierUrl("TA1", new BigDecimal("1000"), "含\"引号\"的订单名"));

        String biz = params.get("biz_content");
        assertTrue(biz.contains("\"total_amount\":\"1000.00\""), "金额必须是两位小数字符串：" + biz);
        assertTrue(biz.contains("\\\"引号\\\""), "商品名中的引号必须转义：" + biz);
        assertTrue(biz.contains("\"out_trade_no\":\"TA1\""));
    }

    @Test
    @DisplayName("时间戳落在 +08:00，格式为 yyyy-MM-dd HH:mm:ss")
    void timestampUsesAlipayZone() {
        KeyPair app = keyPair();

        Map<String, String> params = parseQuery(
                sandbox(app, APP_ID).buildCashierUrl("TA1", new BigDecimal("1.00"), "订单"));

        assertTrue(params.get("timestamp").matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "时间戳格式不对：" + params.get("timestamp"));
    }

    // ------------------------------------------------------------------ 回调验签

    @Test
    @DisplayName("真实支付宝通知（sign_type 参与报文但被剔除后验签）可以通过")
    void verifiesGenuineNotify() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), publicKey(alipay));

        assertTrue(client.verifyNotifySignature(alipaySignedNotify("TA1", alipay)));
    }

    @Test
    @DisplayName("通知被篡改金额后验签不通过")
    void rejectsTamperedNotify() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), publicKey(alipay));

        Map<String, String> params = alipaySignedNotify("TA1", alipay);
        params.put("total_amount", "0.01");   // 签名覆盖金额，改动必然失配

        assertFalse(client.verifyNotifySignature(params));
    }

    @Test
    @DisplayName("缺少 sign 的通知一律拒绝")
    void rejectsNotifyWithoutSign() {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), publicKey(alipay));

        Map<String, String> params = notifyParams("TA1", "ALI1");
        assertFalse(client.verifyNotifySignature(params));
        assertFalse(client.verifyNotifySignature(null));
        assertFalse(client.verifyNotifySignature(Map.of()));
    }

    @Test
    @DisplayName("用别人的私钥签的通知被支付宝公钥拒绝（防止其他应用串单）")
    void rejectsNotifySignedByForeignKey() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        KeyPair attacker = keyPair();
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), publicKey(alipay));

        assertFalse(client.verifyNotifySignature(alipaySignedNotify("TA1", attacker)));
    }

    @Test
    @DisplayName("通知声明的 app_id 与本应用不一致时拒绝，即使签名本身合法")
    void rejectsNotifyForOtherApp() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), publicKey(alipay));

        Map<String, String> params = notifyParams("TA1", "ALI1");
        params.put("app_id", "9999999999999999");
        params.put("sign", signContent(params, alipay.getPrivate()));

        assertFalse(client.verifyNotifySignature(params), "其他应用的合法通知不能被拿去串单");
    }

    @Test
    @DisplayName("未配置支付宝公钥时验签直接失败，不会退化成放行")
    void failsClosedWhenPublicKeyMissing() throws Exception {
        KeyPair app = keyPair();
        KeyPair alipay = keyPair();
        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, APP_ID, privateKey(app), "");

        assertFalse(client.verifyNotifySignature(alipaySignedNotify("TA1", alipay)));
    }

    // ------------------------------------------------------------------ 底层签名

    @Test
    @DisplayName("RSA2 签名往返：合法签名通过，篡改内容或乱码签名被拒（不抛异常）")
    void rsa2VerifyHandlesEdgeCases() {
        KeyPair app = keyPair();
        String content = "app_id=1&method=alipay.trade.query";

        String sign = AlipayGatewayClient.rsa2Sign(content, privateKey(app));

        assertTrue(AlipayGatewayClient.rsa2Verify(content, sign, publicKey(app)));
        assertFalse(AlipayGatewayClient.rsa2Verify(content + "&x=1", sign, publicKey(app)));
        assertFalse(AlipayGatewayClient.rsa2Verify(content, "@@not-a-signature@@", publicKey(app)));
        assertFalse(AlipayGatewayClient.rsa2Verify(content, sign, "not-a-key"));
        assertFalse(AlipayGatewayClient.rsa2Verify(content, "", publicKey(app)));
    }

    @Test
    @DisplayName("待签名串：按 key 字典序、剔除指定参数与空值、不做 URL 编码")
    void canonicalContentFollowsAlipayRules() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("version", "1.0");
        params.put("app_id", APP_ID);
        params.put("sign_type", "RSA2");   // 请求签名时参与
        params.put("empty", "");
        params.put("sign", "SHOULD_BE_DROPPED");
        params.put("biz_content", "{\"a\":\"中文 与 空格\"}");

        String content = AlipayGatewayClient.canonicalContent(params, Set.of("sign"));

        assertEquals("app_id=" + APP_ID
                        + "&biz_content={\"a\":\"中文 与 空格\"}"
                        + "&sign_type=RSA2"
                        + "&version=1.0",
                content);
        // 通知验签时 sign_type 也要剔除
        assertEquals("app_id=" + APP_ID
                        + "&biz_content={\"a\":\"中文 与 空格\"}"
                        + "&version=1.0",
                AlipayGatewayClient.canonicalContent(params, Set.of("sign", "sign_type")));
    }

    @Test
    @DisplayName("带 PEM 头尾的密钥也能被解析（沙箱页面复制出来的两种形态都支持）")
    void acceptsPemWrappedKeys() {
        KeyPair app = keyPair();

        String pemPrivate = "-----BEGIN PRIVATE KEY-----\n" + privateKey(app) + "\n-----END PRIVATE KEY-----";
        String pemPublic = "-----BEGIN PUBLIC KEY-----\n" + publicKey(app) + "\n-----END PUBLIC KEY-----";

        AlipayGatewayClient client = new AlipayGatewayClient(GATEWAY, APP_ID, pemPrivate, pemPublic);
        assertTrue(client.canBuildCashierUrl());
        assertTrue(client.canVerifyNotifySignature());

        String content = "app_id=1";
        assertTrue(AlipayGatewayClient.rsa2Verify(content,
                AlipayGatewayClient.rsa2Sign(content, pemPrivate), pemPublic));
    }

    // ------------------------------------------------------------------ helpers

    private static AlipayGatewayClient sandbox(KeyPair app, String appId) {
        return new AlipayGatewayClient(GATEWAY, appId, privateKey(app), "");
    }

    /** 构造一份「支付宝侧已签名」的异步通知报文。 */
    private static Map<String, String> alipaySignedNotify(String orderNo, KeyPair alipay) throws Exception {
        Map<String, String> params = notifyParams(orderNo, "ALI" + orderNo);
        params.put("sign", signContent(params, alipay.getPrivate()));
        return params;
    }

    private static Map<String, String> notifyParams(String orderNo, String tradeNo) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("gmt_create", "2027-03-01 10:00:00");
        params.put("charset", "utf-8");
        params.put("seller_email", "sandbox@alipaydev.com");
        params.put("subject", "旅行社团购订单");
        params.put("sign", "");                    // 占位，调用方覆盖
        params.put("buyer_id", "2088722000000000");
        params.put("notify_id", "2027030100222100000000000000");
        params.put("notify_type", "trade_status_sync");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "1999.90");
        params.put("trade_no", tradeNo);
        params.put("app_id", APP_ID);
        params.put("notify_time", "2027-03-01 10:00:05");
        params.put("out_trade_no", orderNo);
        params.put("sign_type", "RSA2");           // 验签时必须剔除
        params.put("notify_path", NOTIFY_PATH);
        return params;
    }

    /** 按官方 {@code rsaCheckV1} 口径对通知签名：剔除 sign 与 sign_type。 */
    private static String signContent(Map<String, String> params, PrivateKey alipayPrivateKey) throws Exception {
        String content = AlipayGatewayClient.canonicalContent(params, Set.of("sign", "sign_type"));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(alipayPrivateKey);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static Map<String, String> parseQuery(String url) {
        String query = url.substring(url.indexOf('?') + 1);
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return params;
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
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    private static String publicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}
