package com.travelagency.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发起支付响应，对齐契约 PaymentStart。
 * paymentUrl 为支付宝沙箱收银台地址，expiresAt 为该次支付的过期时间。
 */
public record PaymentStartResponse(
        String orderNo,
        String paymentNo,
        String channel,
        BigDecimal amount,
        String paymentUrl,
        LocalDateTime expiresAt) {
}
