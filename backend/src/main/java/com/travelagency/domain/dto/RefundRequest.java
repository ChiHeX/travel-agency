package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 退款申请请求，对齐契约 RefundCreateRequest（required: [reason]，minLength: 2，maxLength: 500）。
 *
 * <p>长度按 Unicode 码点计数（{@link CodePointLength}）：契约的 {@code maxLength} 是
 * JSON Schema 口径，数的是字符，而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 是 1 个码点却是 2 个码元。</p>
 */
public record RefundRequest(
        @NotBlank(message = "退款原因不能为空")
        @CodePointLength(min = 2, max = 500, message = "退款原因长度应为 2-500 字") String reason) {
}
