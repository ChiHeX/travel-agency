package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 在线咨询回复请求，对齐契约 {@code ConsultationReplyRequest}。
 *
 * <p>长度按 Unicode 码点计数（{@link CodePointLength}），与契约 {@code maxLength: 2000} 同口径。</p>
 */
public record ConsultationReplyRequest(
        @NotBlank(message = "回复内容不能为空")
        @CodePointLength(max = 2000, message = "回复内容不能超过 2000 字") String content) {
}
