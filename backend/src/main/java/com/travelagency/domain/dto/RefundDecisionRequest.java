package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 退款审核请求，对齐契约 RefundDecisionRequest。
 *
 * <p>契约把 {@code comment} 列为 required 且 minLength: 1（同意与拒绝共用同一 schema），
 * 因此两个动作都必须给出审核意见。</p>
 *
 * <p>整个 requestBody 在 {@code POST /admin/refunds/{refundId}/approve} 上是可选的，
 * 但「不提交 body」与「提交空意见」是两回事：前者由 {@code @RequestBody(required = false)}
 * 放行，后者必须被本节校验拒绝。</p>
 */
public record RefundDecisionRequest(
        @NotBlank(message = "审核意见不能为空")
        @Size(max = 500, message = "审核意见不能超过 500 字") String comment) {
}
