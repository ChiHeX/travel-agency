package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 退款申请请求，对齐契约 RefundCreateRequest（required: [reason]，minLength: 2，maxLength: 500）。
 *
 * <p>长度按 Unicode 码点计数（{@link CodePointLength}），与契约的 JSON Schema 口径一致；
 * {@code @Size} 数的是 UTF-16 码元，会把契约允许的 emoji 理由误判成超长。</p>
 */
public record RefundRequest(
        @NotBlank(message = "退款原因不能为空")
        @CodePointLength(min = 2, max = 500, message = "退款原因长度应为 2-500 字") String reason) {
}
