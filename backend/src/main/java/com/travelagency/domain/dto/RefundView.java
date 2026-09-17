package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Refund;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款对外视图，对齐契约 Refund。orderNo 需联查补充。
 */
public record RefundView(
        Long id,
        String orderNo,
        Long userId,
        BigDecimal amount,
        String reason,
        String originalOrderStatus,
        String status,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        String reviewComment,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static RefundView from(Refund refund, String orderNo) {
        return new RefundView(refund.id, orderNo, refund.userId, refund.amount, refund.reason,
                refund.originalOrderStatus, refund.status, refund.reviewedBy, refund.reviewedAt,
                refund.reviewComment, refund.createdAt, refund.updatedAt);
    }
}
