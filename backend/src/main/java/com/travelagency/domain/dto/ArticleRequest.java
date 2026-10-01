package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 攻略新增/修改请求，对齐契约 ArticleUpsertRequest 的字段长度约束。
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是出现「契约允许、实现却回 422」（200 个 emoji 的标题，
 * 码点 200 ≤ 200 合法，码元却是 400）。库内列宽同样是码点口径
 * （{@code VARCHAR(200)} 在 utf8mb4 下就是 200 个字符），三边一致。</p>
 */
public record ArticleRequest(
        @NotBlank(message = "攻略标题不能为空")
        @CodePointLength(min = 2, max = 200, message = "攻略标题长度应为 2-200 个字符") String title,
        @CodePointLength(max = 500, message = "攻略摘要不能超过 500 个字符") String summary,
        @NotBlank(message = "攻略内容不能为空")
        @CodePointLength(max = 100000, message = "攻略内容不能超过 100000 个字符") String content,
        @CodePointLength(max = 64, message = "所属城市不能超过 64 个字符") String city,
        @CodePointLength(max = 128, message = "目的地不能超过 128 个字符") String destination,
        Long attractionId,
        @CodePointLength(max = 500, message = "封面地址不能超过 500 个字符") String coverUrl) {
}
