package com.travelagency.web.controller;

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
 * <p>当前为 HMAC 沙箱验签适配点：调用方用共享密钥对 orderNo|tradeNo|result 计算 HmacSHA256 并 Base64。
 * 共享密钥必须通过 {@code ALIPAY_CALLBACK_SECRET} 注入，未配置时一律拒绝（fail-closed）。
 * 回调金额会与订单应付金额比对，不一致即拒绝。</p>
 *
 * <p>接入真实支付宝沙箱 SDK 时，应在适配器层替换为官方签名校验（校验 app_id 与支付宝公钥），
 * 业务层只接受验签后的结果；金额核对逻辑可继续保留。</p>
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final OrderService orderService;
    private final String callbackSecret;

    public PaymentController(
            OrderService orderService,
            @Value("${app.integrations.alipay.callback-secret:}") String callbackSecret) {
        this.orderService = orderService;
        this.callbackSecret = callbackSecret;
    }

    /**
     * 契约约定回调返回 text/plain 的 success/failure。
     * 同时声明 ALL_VALUE，避免第三方网关或调用方携带 Accept 头时被内容协商拒成 406。
     */
    @PostMapping(value = "/alipay/notify", produces = {MediaType.TEXT_PLAIN_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<String> notify(@RequestParam Map<String, String> params) {
        String orderNo = firstNonBlank(params.get("orderNo"), params.get("out_trade_no"));
        String tradeNo = firstNonBlank(params.get("tradeNo"), params.get("trade_no"));
        String result = firstNonBlank(params.get("result"), params.get("trade_status"));
        String signature = firstNonBlank(params.get("signature"), params.get("sign"));

        if (orderNo == null || tradeNo == null || result == null) {
            return ResponseEntity.ok("failure");
        }
        if (!isSuccessResult(result) || !verify(orderNo, tradeNo, result, signature)) {
            log.warn("支付宝回调验签失败或结果非成功：orderNo={}, result={}", orderNo, result);
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
     * 回调验签。
     *
     * <p>密钥未配置时一律拒绝（fail-closed）。此前该密钥在 {@code @Value} 里带有一个写死在
     * 源码中的默认值，等于把共享密钥随仓库公开：任何读到代码的人都能自行算出合法签名，
     * 伪造回调把任意订单标记为已支付。现在强制要求通过
     * {@code ALIPAY_CALLBACK_SECRET} 环境变量 / 配置项注入，缺失即拒绝。</p>
     */
    private boolean verify(String orderNo, String tradeNo, String result, String signature) {
        if (callbackSecret == null || callbackSecret.isBlank()) {
            log.error("未配置 app.integrations.alipay.callback-secret，已拒绝该支付回调（fail-closed）");
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
