package com.travelagency.common.alipay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 支付宝沙箱网关适配器（纯 JDK 实现，不依赖第三方 SDK）。
 *
 * <p>为什么是自实现而不是 {@code alipay-sdk-java}：本机开发环境拉取 Maven 依赖受网络与代理限制，
 * 且支付宝网关协议本身很薄 —— 就是「公共参数按 key 字典序拼串 → SHA256withRSA 签名 → Base64」。
 * 本类只做两件事，且用的都是支付宝官方签名规范（RSA2），因此契约里
 * 「支付通知必须按支付宝规范验签」的要求成立。</p>
 *
 * <p><b>已实测</b>：2026-09-18 用同一套协议调 {@code alipay.trade.query}，
 * 沙箱返回业务错误「交易不存在」而非 {@code INVALID-SIGNATURE}，说明我方签名被支付宝接受；
 * 并且支付宝响应报文自带的 {@code sign} 用支付宝公钥验签通过。</p>
 *
 * <p>配置全部来自环境变量，缺失时 {@link #canBuildCashierUrl()} 为 false，
 * 调用方据此回退到「适配点未接入」的行为，而不是抛异常把主链路打死。</p>
 */
@Component
public class AlipayGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(AlipayGatewayClient.class);

    /** 电脑网站支付：生成可直接在浏览器打开的收银台链接。 */
    private static final String METHOD_PAGE_PAY = "alipay.trade.page.pay";
    /** 电脑网站支付的固定产品码。 */
    private static final String PRODUCT_CODE_PAGE_PAY = "FAST_INSTANT_TRADE_PAY";
    private static final String SIGN_TYPE = "RSA2";

    /** 支付宝的时间戳必须落在 +08:00，且与支付宝服务器时间相差不能超过窗口。 */
    private static final ZoneId ALIPAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ALIPAY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 请求签名：支付宝规则是「剔除 sign 与空值」，sign_type 参与签名
     * （已实测：把 sign_type 拼进待签名串签名被网关接受）。
     */
    private static final Set<String> REQUEST_SIGN_EXCLUDED = Set.of("sign");

    /**
     * 异步通知验签：支付宝官方 {@code AlipaySignature.rsaCheckV1} 的规则是剔除 sign 与 sign_type。
     * 与请求签名不同，这里必须把 sign_type 也剔掉。
     */
    private static final Set<String> NOTIFY_SIGN_EXCLUDED = Set.of("sign", "sign_type");

    private final String gatewayUrl;
    private final String appId;
    private final String appPrivateKey;
    private final String alipayPublicKey;

    public AlipayGatewayClient(
            @Value("${app.integrations.alipay.gateway-url:https://openapi-sandbox.dl.alipaydev.com/gateway.do}")
            String gatewayUrl,
            @Value("${app.integrations.alipay.app-id:}") String appId,
            @Value("${app.integrations.alipay.app-private-key:}") String appPrivateKey,
            @Value("${app.integrations.alipay.alipay-public-key:}") String alipayPublicKey) {
        this.gatewayUrl = gatewayUrl;
        this.appId = appId;
        this.appPrivateKey = appPrivateKey;
        this.alipayPublicKey = alipayPublicKey;
    }

    /**
     * 是否具备生成真实收银台链接的条件（APPID + 应用私钥）。
     * 缺任一项时调用方应回退到占位行为，不能把下单/支付接口打成 500。
     */
    public boolean canBuildCashierUrl() {
        return notBlank(gatewayUrl) && notBlank(appId) && notBlank(appPrivateKey);
    }

    /**
     * 是否具备用支付宝公钥验签的能力。配置齐备时回调一律走官方 RSA2 验签。
     */
    public boolean canVerifyNotifySignature() {
        return notBlank(alipayPublicKey);
    }

    /** 网关地址。未配置沙箱密钥时，调用方用它作为「适配点未接入」的占位地址。 */
    public String gatewayUrl() {
        return gatewayUrl;
    }

    /** 供日志/排障使用，绝不输出密钥内容本身。 */
    public String describeConfiguration() {
        return "appId=" + (notBlank(appId) ? "已配置" : "未配置")
                + ", appPrivateKey=" + (notBlank(appPrivateKey) ? "已配置" : "未配置")
                + ", alipayPublicKey=" + (notBlank(alipayPublicKey) ? "已配置" : "未配置")
                + ", gateway=" + gatewayUrl;
    }

    /**
     * 生成支付宝电脑网站支付的真实收银台地址（{@code alipay.trade.page.pay}）。
     *
     * <p>生成的链接可直接在浏览器打开：沙箱环境下用<b>沙箱买家账号</b>登录付款。</p>
     *
     * @throws IllegalStateException 未配置 APPID / 应用私钥
     */
    public String buildCashierUrl(String outTradeNo, BigDecimal amount, String subject) {
        if (!canBuildCashierUrl()) {
            throw new IllegalStateException(
                    "支付宝沙箱未配置（需要 ALIPAY_APP_ID 与 ALIPAY_APP_PRIVATE_KEY）：" + describeConfiguration());
        }
        Map<String, String> params = baseParams(METHOD_PAGE_PAY);
        params.put("biz_content", "{\"out_trade_no\":\"" + jsonEscape(outTradeNo)
                + "\",\"total_amount\":\"" + formatAmount(amount)
                + "\",\"subject\":\"" + jsonEscape(subject)
                + "\",\"product_code\":\"" + PRODUCT_CODE_PAGE_PAY + "\"}");
        return gatewayUrl + "?" + signedQuery(params);
    }

    /**
     * 校验支付宝异步通知的签名，并按契约要求核对发起方 app_id。
     *
     * <p>返回 false 的场景全部按「拒绝该回调」处理（fail-closed），包括：未配置支付宝公钥、
     * 通知缺少 sign、app_id 与本地配置不一致、验签不通过。</p>
     */
    public boolean verifyNotifySignature(Map<String, String> notifyParams) {
        if (!canVerifyNotifySignature()) {
            log.error("未配置 app.integrations.alipay.alipay-public-key，已拒绝该支付回调（fail-closed）");
            return false;
        }
        if (notifyParams == null || notifyParams.isEmpty()) {
            return false;
        }
        String declaredSign = notifyParams.get("sign");
        if (!notBlank(declaredSign)) {
            return false;
        }
        // 商户校验：通知声明的 app_id 必须与本应用一致，避免其他应用的合法通知被拿来串单。
        if (notBlank(appId)) {
            String declaredAppId = notifyParams.get("app_id");
            if (!appId.equals(declaredAppId)) {
                log.warn("支付回调的 app_id 与本应用不一致，已拒绝：declared={}", declaredAppId);
                return false;
            }
        }
        String content = canonicalContent(notifyParams, NOTIFY_SIGN_EXCLUDED);
        return rsa2Verify(content, declaredSign, alipayPublicKey);
    }

    // ------------------------------------------------------------------
    // 签名与验签
    // ------------------------------------------------------------------

    /** 支付宝公共请求参数。 */
    private Map<String, String> baseParams(String method) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", appId);
        params.put("method", method);
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", SIGN_TYPE);
        params.put("timestamp", ALIPAY_TIMESTAMP.format(Instant.now().atZone(ALIPAY_ZONE)));
        params.put("version", "1.0");
        return params;
    }

    /**
     * 按支付宝规则生成待签名串：剔除指定参数与空值，其余按 key 字典序，拼成 {@code k=v&k=v}。
     * 注意待签名串<b>不做 URL 编码</b>（编码只发生在拼成最终请求体/查询串时）。
     */
    static String canonicalContent(Map<String, String> params, Set<String> excluded) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder content = new StringBuilder();
        for (String key : keys) {
            if (excluded.contains(key)) {
                continue;
            }
            String value = params.get(key);
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (content.length() > 0) {
                content.append('&');
            }
            content.append(key).append('=').append(value);
        }
        return content.toString();
    }

    /** 生成带签名的 GET 查询串（值做 URL 编码）。 */
    private String signedQuery(Map<String, String> params) {
        String sign = rsa2Sign(canonicalContent(params, REQUEST_SIGN_EXCLUDED), appPrivateKey);
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            query.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue())).append('&');
        }
        query.append("sign=").append(urlEncode(sign));
        return query.toString();
    }

    /** RSA2（SHA256withRSA）签名，返回 Base64。 */
    static String rsa2Sign(String content, String privateKeyBase64) {
        try {
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(decodeKey(privateKeyBase64)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("支付宝 RSA2 签名失败：" + ex.getMessage(), ex);
        }
    }

    /** 用支付宝公钥做 RSA2 验签；任何异常都视为验签失败。 */
    static boolean rsa2Verify(String content, String signBase64, String publicKeyBase64) {
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(decodeKey(publicKeyBase64)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(stripWhitespace(signBase64)));
        } catch (Exception ex) {
            log.warn("支付宝 RSA2 验签失败：{}", ex.getMessage());
            return false;
        }
    }

    /**
     * 解出密钥字节。容忍两种情况：① 只有 Base64 主体（沙箱页面直接复制出来的形态）；
     * ② 带 PEM 头尾的完整片段。
     */
    private static byte[] decodeKey(String raw) {
        String normalized = stripWhitespace(raw)
                .replace("-----BEGINPRIVATEKEY-----", "")
                .replace("-----ENDPRIVATEKEY-----", "")
                .replace("-----BEGINPUBLICKEY-----", "")
                .replace("-----ENDPUBLICKEY-----", "")
                .replace("-----BEGINRSAPRIVATEKEY-----", "")
                .replace("-----ENDRSAPRIVATEKEY-----", "")
                .replace("-----BEGINRSAPUBLICKEY-----", "")
                .replace("-----ENDRSAPUBLICKEY-----", "");
        return Base64.getDecoder().decode(normalized);
    }

    private static String stripWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s", "");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
