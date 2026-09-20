package com.travelagency.web.controller;

import com.travelagency.common.alipay.AlipayGatewayClient;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付回调入口的安全边界。
 *
 * <p>重点覆盖四点：① 共享密钥未配置时必须 fail-closed，绝不能退回到源码里公开可见的默认值；
 * ② 回调金额必须交给业务层与订单应付金额核对，缺失金额不能当作“无需核对”放行；
 * ③ 回调入口按配置在三条路径间路由（官方 SDK 验签 / RSA2 半配置直接拒绝 / 本地 HMAC 回退），
 * 且<b>绝不互相降级</b>；④ 官方 RSA2 验签本身的规则另见 {@code AlipayGatewayClientTest}。</p>
 *
 * <p>③ 的写法要点：给一份<b>本地 HMAC 签名算对</b>的回调参数，再断言仍返回 failure ——
 * 这样才能证明失败是"真的拒绝了"，而不是"其实悄悄退回了 HMAC 只是恰好没过"。</p>
 */
class PaymentControllerTest {

    private static final String SECRET = "unit-test-callback-secret";

    /**
     * 回调入口按配置在两条验签路径间自动选择。本测试类专门覆盖 <b>HMAC 回退路径</b>，
     * 因此让适配器声明「没有配置支付宝公钥」，从而走自建共享密钥验签。
     */
    private static AlipayGatewayClient hmacFallbackClient() {
        AlipayGatewayClient client = mock(AlipayGatewayClient.class);
        when(client.canVerifyNotifySignature()).thenReturn(false);
        return client;
    }

    private static String sign(String orderNo, String tradeNo, String result) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal((orderNo + "|" + tradeNo + "|" + result).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, String> params(String orderNo, String tradeNo, String result,
                                              String amount, String signature) {
        Map<String, String> p = new HashMap<>();
        p.put("orderNo", orderNo);
        p.put("tradeNo", tradeNo);
        p.put("result", result);
        if (amount != null) {
            p.put("total_amount", amount);
        }
        if (signature != null) {
            p.put("signature", signature);
        }
        return p;
    }

    @Test
    @DisplayName("未配置共享密钥时一律拒绝回调（fail-closed），且不触碰订单")
    void rejectsEverythingWhenSecretMissing() {
        OrderService orderService = mock(OrderService.class);
        PaymentController controller = new PaymentController(orderService, hmacFallbackClient(), "");

        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", "100.00", "whatever"));

        assertEquals("failure", response.getBody());
        verify(orderService, never()).markPaid(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("密钥已配置但缺少签名时拒绝")
    void rejectsWhenSignatureMissing() {
        OrderService orderService = mock(OrderService.class);
        PaymentController controller = new PaymentController(orderService, hmacFallbackClient(), SECRET);

        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", "100.00", null));

        assertEquals("failure", response.getBody());
        verify(orderService, never()).markPaid(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("回调缺少 total_amount 时拒绝，不进入业务层")
    void rejectsWhenAmountMissing() {
        OrderService orderService = mock(OrderService.class);
        PaymentController controller = new PaymentController(orderService, hmacFallbackClient(), SECRET);

        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", null, sign("TA1", "ALI1", "SUCCESS")));

        assertEquals("failure", response.getBody());
        verify(orderService, never()).markPaid(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("验签通过时把回调金额交给业务层核对")
    void passesAmountToServiceOnValidSignature() {
        OrderService orderService = mock(OrderService.class);
        PaymentController controller = new PaymentController(orderService, hmacFallbackClient(), SECRET);

        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", "199.90", sign("TA1", "ALI1", "SUCCESS")));

        assertEquals("success", response.getBody());
        verify(orderService).markPaid("TA1", "ALI1", new BigDecimal("199.90"));
    }

    @Test
    @DisplayName("业务层因金额不符拒绝时，回调返回 failure 而不是 success")
    void returnsFailureWhenAmountMismatch() {
        OrderService orderService = mock(OrderService.class);
        doThrow(new BusinessException(409, "PAYMENT_AMOUNT_MISMATCH", "回调金额与订单应付金额不一致"))
                .when(orderService).markPaid(anyString(), anyString(), any());
        PaymentController controller = new PaymentController(orderService, hmacFallbackClient(), SECRET);

        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", "0.01", sign("TA1", "ALI1", "SUCCESS")));

        assertEquals("failure", response.getBody());
    }

    // ---------------------------------------------------------------- 验签路径的路由

    @Test
    @DisplayName("官方 SDK 验签通过时把回调金额交给业务层核对")
    void passesAmountToServiceWhenOfficialSdkVerifies() {
        OrderService orderService = mock(OrderService.class);
        AlipayGatewayClient client = mock(AlipayGatewayClient.class);
        when(client.canVerifyNotifySignature()).thenReturn(true);
        when(client.verifyNotifySignature(any())).thenReturn(true);
        PaymentController controller = new PaymentController(orderService, client, SECRET);

        // 官方路径不需要本地 HMAC 的 signature 参数，故意不传
        var response = controller.notify(params("TA1", "ALI1", "TRADE_SUCCESS", "199.90", null));

        assertEquals("success", response.getBody());
        verify(orderService).markPaid("TA1", "ALI1", new BigDecimal("199.90"));
    }

    @Test
    @DisplayName("官方 SDK 验签不通过时返回 failure，即使本地 HMAC 签名完全合法也不降级放行")
    void rejectsWhenOfficialSdkVerificationFails() {
        OrderService orderService = mock(OrderService.class);
        AlipayGatewayClient client = mock(AlipayGatewayClient.class);
        when(client.canVerifyNotifySignature()).thenReturn(true);
        when(client.verifyNotifySignature(any())).thenReturn(false);
        PaymentController controller = new PaymentController(orderService, client, SECRET);

        // 这一份参数的本地 HMAC 签名是算对的：若实现偷偷回退到 HMAC，就会变成 success
        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", "199.90", sign("TA1", "ALI1", "SUCCESS")));

        assertEquals("failure", response.getBody(), "官方 SDK 验签失败必须拒绝，不能退回 HMAC");
        verify(orderService, never()).markPaid(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("RSA2 半配置（公钥与 APPID 只配其一）时一律拒绝，不降级到 HMAC")
    void failsClosedOnPartialRsa2Configuration() {
        OrderService orderService = mock(OrderService.class);
        AlipayGatewayClient client = mock(AlipayGatewayClient.class);
        when(client.canVerifyNotifySignature()).thenReturn(false);
        when(client.isRsa2ConfigurationIncomplete()).thenReturn(true);
        when(client.describeConfiguration()).thenReturn("appId=未配置, alipayPublicKey=已配置");
        PaymentController controller = new PaymentController(orderService, client, SECRET);

        // 同样给一份本地 HMAC 签名算对的回调：半配置下必须拒绝，而不是"退回去用 HMAC 也能过"
        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", "199.90", sign("TA1", "ALI1", "SUCCESS")));

        assertEquals("failure", response.getBody(), "半配置必须拒绝回调");
        verify(orderService, never()).markPaid(anyString(), anyString(), any());
    }
}
