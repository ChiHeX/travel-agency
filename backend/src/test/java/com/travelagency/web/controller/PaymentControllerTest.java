package com.travelagency.web.controller;

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

/**
 * 支付回调入口的安全边界。
 *
 * <p>重点覆盖两点：① 共享密钥未配置时必须 fail-closed，绝不能退回到源码里公开可见的默认值；
 * ② 回调金额必须交给业务层与订单应付金额核对，缺失金额不能当作“无需核对”放行。</p>
 */
class PaymentControllerTest {

    private static final String SECRET = "unit-test-callback-secret";

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
        PaymentController controller = new PaymentController(orderService, "");

        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", "100.00", "whatever"));

        assertEquals("failure", response.getBody());
        verify(orderService, never()).markPaid(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("密钥已配置但缺少签名时拒绝")
    void rejectsWhenSignatureMissing() {
        OrderService orderService = mock(OrderService.class);
        PaymentController controller = new PaymentController(orderService, SECRET);

        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", "100.00", null));

        assertEquals("failure", response.getBody());
        verify(orderService, never()).markPaid(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("回调缺少 total_amount 时拒绝，不进入业务层")
    void rejectsWhenAmountMissing() {
        OrderService orderService = mock(OrderService.class);
        PaymentController controller = new PaymentController(orderService, SECRET);

        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", null, sign("TA1", "ALI1", "SUCCESS")));

        assertEquals("failure", response.getBody());
        verify(orderService, never()).markPaid(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("验签通过时把回调金额交给业务层核对")
    void passesAmountToServiceOnValidSignature() {
        OrderService orderService = mock(OrderService.class);
        PaymentController controller = new PaymentController(orderService, SECRET);

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
        PaymentController controller = new PaymentController(orderService, SECRET);

        var response = controller.notify(params("TA1", "ALI1", "SUCCESS", "0.01", sign("TA1", "ALI1", "SUCCESS")));

        assertEquals("failure", response.getBody());
    }
}
