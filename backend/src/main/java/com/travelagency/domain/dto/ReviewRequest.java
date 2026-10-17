package com.travelagency.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 发表评价请求，对齐契约 ReviewRequest。
 *
 * <p>长度按 Unicode 码点计数（{@link CodePointLength}）：契约的 {@code maxLength} 是
 * JSON Schema 口径，数的是字符，而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 是 1 个码点却是 2 个码元，1000 个 emoji 的评价会被 {@code @Size} 误判成超长。</p>
 */
public record ReviewRequest(
        @NotNull(message = "评分不能为空") @Min(value = 1, message = "评分最低为 1 星") @Max(value = 5, message = "评分最高为 5 星") Integer rating,
        @NotBlank(message = "评价内容不能为空") @CodePointLength(max = 1000, message = "评价内容不能超过 1000 字") String content) {
}
