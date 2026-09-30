package com.travelagency.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 评价请求，对齐契约 {@code ReviewCreateRequest}。
 *
 * <p>{@code content} 的长度按 Unicode 码点计数（{@link CodePointLength}），与契约
 * {@code maxLength: 1000} 同口径：{@code @Size} 数的是 UTF-16 码元，一段 emoji 评论会被误拒。</p>
 */
public record ReviewRequest(
        @NotNull(message = "评分不能为空") @Min(value = 1, message = "评分最低为 1 星") @Max(value = 5, message = "评分最高为 5 星") Integer rating,
        @NotBlank(message = "评价内容不能为空")
        @CodePointLength(max = 1000, message = "评价内容不能超过 1000 字") String content) {
}
