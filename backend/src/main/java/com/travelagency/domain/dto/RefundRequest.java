package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 退款申请请求，对齐契约 RefundCreateRequest（required: [reason]，minLength: 2，maxLength: 500）。
 */
public record RefundRequest(
        @NotBlank(message = "退款原因不能为空")
        @Size(min = 2, max = 500, message = "退款原因长度应为 2-500 字") String reason) {
}
