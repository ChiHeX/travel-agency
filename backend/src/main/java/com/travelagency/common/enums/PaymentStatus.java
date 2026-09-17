package com.travelagency.common.enums;

/**
 * 支付状态，取值对齐契约 PaymentStatus 枚举 [UNPAID, PENDING, PAID, FAILED, CLOSED, REFUNDED]。
 */
public final class PaymentStatus {
    public static final String UNPAID = "UNPAID";
    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    public static final String FAILED = "FAILED";
    public static final String CLOSED = "CLOSED";
    public static final String REFUNDED = "REFUNDED";

    private PaymentStatus() {
    }
}
