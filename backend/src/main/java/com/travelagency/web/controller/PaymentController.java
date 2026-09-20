package com.travelagency.web.controller;

import com.travelagency.common.alipay.AlipayGatewayClient;
import com.travelagency.domain.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * 支付宝异步通知入口，对齐契约 POST /payments/alipay/notify：
 * 接收 application/x-www-form-urlencoded，按第三方交易号幂等处理，返回 text/plain 的 success/failure。
 *
 * <p><b>验签按配置分三条路径：</b></p>
 * <ol>
 *   <li><b>官方路径（{@code ALIPAY_PUBLIC_KEY} 与 {@code ALIPAY_APP_ID} 都配置时）</b>：
 *       调用 {@code alipay-sdk-java} 的 {@code AlipaySignature.rsaCheckV1} 做 RSA2 验签，
 *       并核对通知声明的 {@code app_id}（配置了 {@code ALIPAY_SELLER_ID} 时再核对 {@code seller_id}）。
 *       这是契约与架构文档要求的口径。</li>
 *   <li><b>半配置路径（两项只配了其一）</b>：一律拒绝。使用者显然想走官方验签却没配完，
 *       此时若退回 HMAC 就是把验签强度静默降级，与文档承诺的 fail-closed 相悖。</li>
 *   <li><b>本地开发回退路径（两项都没配）</b>：沿用自建 HMAC 适配点，共享密钥由
 *       {@code ALIPAY_CALLBACK_SECRET} 注入，未配置时一律拒绝（fail-closed）。
 *       它只用于本地没有沙箱密钥时把链路跑通，<b>不代表支付宝官方验签</b>。</li>
 * </ol>
 *
 * <p>三条路径都保留金额核对：回调金额会与订单应付金额比对，不一致即拒绝。</p>
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final OrderService orderService;
    private final AlipayGatewayClient alipayGatewayClient;
    private final String callbackSecret;

    public PaymentController(
            OrderService orderService,
            AlipayGatewayClient alipayGatewayClient,
            @Value("${app.integrations.alipay.callback-secret:}") String callbackSecret) {
        this.orderService = orderService;
        this.alipayGatewayClient = alipayGatewayClient;
        this.callbackSecret = callbackSecret;
    }

    /**
     * 契约约定回调返回 text/plain 的 success/failure。
     * 同时声明 ALL_VALUE，避免第三方网关或调用方携带 Accept 头时被内容协商拒成 406。
     */
    @PostMapping(value = "/alipay/notify", produces = {MediaType.TEXT_PLAIN_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<String> notify(@RequestParam Map<String, String> params) {
        String orderNo = firstNonBlank(params.get("out_trade_no"), params.get("orderNo"));
        String tradeNo = firstNonBlank(params.get("trade_no"), params.get("tradeNo"));
        String result = firstNonBlank(params.get("trade_status"), params.get("result"));

        if (orderNo == null || tradeNo == null || result == null) {
            return ResponseEntity.ok("failure");
        }
        if (!isSuccessResult(result)) {
            return ResponseEntity.ok("failure");
        }
        if (!verified(params, orderNo, tradeNo, result)) {
            return ResponseEntity.ok("failure");
        }
        // 金额是支付宝异步通知的必带字段。缺失或无法解析时直接拒绝，
        // 避免「没有金额可比对」被当成核对通过。
        BigDecimal amount = parseAmount(params);
        if (amount == null) {
            log.warn("支付宝回调缺少或无法解析 total_amount，已拒绝：orderNo={}", orderNo);
            return ResponseEntity.ok("failure");
        }
        try {
            orderService.markPaid(orderNo, tradeNo, amount);
            return ResponseEntity.ok("success");
        } catch (Exception ex) {
            log.error("支付宝回调处理失败：orderNo={}, tradeNo={}", orderNo, tradeNo, ex);
            return ResponseEntity.ok("failure");
        }
    }

    private boolean isSuccessResult(String result) {
        return "SUCCESS".equalsIgnoreCase(result)
                || "TRADE_SUCCESS".equalsIgnoreCase(result)
                || "TRADE_FINISHED".equalsIgnoreCase(result);
    }

    /**
     * 按配置选择验签路径：公钥与 APPID 配齐走官方 RSA2 验签；只配其一直接拒绝；
     * 两项都没配才回退到自建 HMAC 适配点。
     */
    private boolean verified(Map<String, String> params, String orderNo, String tradeNo, String result) {
        if (alipayGatewayClient.canVerifyNotifySignature()) {
            boolean ok = alipayGatewayClient.verifyNotifySignature(params);
            if (!ok) {
                log.warn("支付回调未通过支付宝官方 SDK 验签，已拒绝：orderNo={}", orderNo);
            }
            return ok;
        }
        if (alipayGatewayClient.isRsa2ConfigurationIncomplete()) {
            // 公钥与 APPID 只配了其一：使用者想走官方验签但没配完。
            // 这里必须拒绝而不是降级到 HMAC —— 少配一个环境变量不该悄悄降低验签强度。
            log.error("支付宝 RSA2 验签配置不完整（ALIPAY_PUBLIC_KEY 与 ALIPAY_APP_ID 必须同时配置），"
                    + "已拒绝该支付回调（fail-closed）：{}", alipayGatewayClient.describeConfiguration());
            return false;
        }
        return verifySharedSecret(orderNo, tradeNo, result,
                firstNonBlank(params.get("signature"), params.get("sign")));
    }

    /**
     * 自建 HMAC 适配点（本地开发回退路径）。
     *
     * <p>密钥未配置时一律拒绝（fail-closed）。此前该密钥在 {@code @Value} 里带有一个写死在
     * 源码中的默认值，等于把共享密钥随仓库公开：任何读到代码的人都能自行算出合法签名，
     * 伪造回调把任意订单标记为已支付。现在强制要求通过
     * {@code ALIPAY_CALLBACK_SECRET} 环境变量 / 配置项注入，缺失即拒绝。</p>
     */
    private boolean verifySharedSecret(String orderNo, String tradeNo, String result, String signature) {
        if (callbackSecret == null || callbackSecret.isBlank()) {
            log.error("既未配置支付宝公钥，也未配置 app.integrations.alipay.callback-secret，已拒绝该支付回调（fail-closed）");
            return false;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((orderNo + "|" + tradeNo + "|" + result).getBytes(StandardCharsets.UTF_8));
            byte[] actual = Base64.getDecoder().decode(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 解析回调声明的支付金额。支付宝异步通知使用 {@code total_amount}；
     * 同时兼容沙箱适配层可能使用的 {@code totalAmount} / {@code amount} 字段名。
     */
    private static BigDecimal parseAmount(Map<String, String> params) {
        String raw = firstNonBlank(params.get("total_amount"), params.get("totalAmount"), params.get("amount"));
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
