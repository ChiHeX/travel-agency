package com.travelagency.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * 退款审核请求，对齐契约 RefundDecisionRequest。
 * comment 同意时可为空；拒绝时业务层强制要求填写。
 */
public record RefundDecisionRequest(
        @Size(max = 500, message = "审核意见不能超过 500 字") String comment) {
}
