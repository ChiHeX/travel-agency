package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 在线咨询提问请求，对齐契约 {@code ConsultationCreateRequest}。
 *
 * <p>长度按 Unicode 码点计数（{@link CodePointLength}），与契约 {@code maxLength} 同口径：
 * {@code @Size} 数的是 UTF-16 码元，emoji 内容会被误判成超长。</p>
 */
public record ConsultationRequest(
        @NotBlank(message = "问题标题不能为空")
        @CodePointLength(max = 100, message = "问题标题不能超过 100 字") String title,
        @NotBlank(message = "问题内容不能为空")
        @CodePointLength(max = 2000, message = "问题内容不能超过 2000 字") String content) {
}
