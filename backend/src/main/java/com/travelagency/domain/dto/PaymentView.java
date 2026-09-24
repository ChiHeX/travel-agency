package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付对外视图，对齐契约 Payment。orderNo 需联查补充。
 */
public record PaymentView(
        Long id,
        String paymentNo,
        String orderNo,
        String channel,
        BigDecimal amount,
        String status,
        String thirdPartyTradeNo,
        LocalDateTime paidAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PaymentView from(Payment payment, String orderNo) {
        return new PaymentView(payment.id, payment.paymentNo, orderNo, payment.channel, payment.amount,
                payment.status, payment.thirdPartyTradeNo, payment.paidAt, payment.createdAt, payment.updatedAt);
    }
}
