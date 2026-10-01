package com.travelagency.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;
import java.util.List;

/**
 * 地点指南新增/修改请求，对齐契约 {@code PlaceGuideRequest}。
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是出现「契约允许、实现却回 422」。
 * {@code places} 上的 {@code @Size(min = 2, max = 50)} <b>不在此列</b>：它数的是列表元素个数，
 * 与文本长度无关，继续用 {@code @Size}。</p>
 */
public record PlaceGuideRequest(
        @NotBlank(message = "指南标题不能为空")
        @CodePointLength(min = 2, max = 200, message = "指南标题长度应为 2-200 个字符") String title,
        @CodePointLength(max = 500, message = "指南摘要不能超过 500 个字符") String summary,
        @NotBlank(message = "所属城市不能为空")
        @CodePointLength(max = 64, message = "所属城市不能超过 64 个字符") String city,
        @CodePointLength(max = 128, message = "目的地不能超过 128 个字符") String destination,
        @CodePointLength(max = 500, message = "封面地址不能超过 500 个字符") String coverUrl,
        @NotEmpty(message = "地点列表不能为空")
        @Size(min = 2, max = 50, message = "指南需要 2 到 50 个地点") List<@Valid Place> places) {

    public record Place(@NotNull(message = "地点不能为空") Long attractionId,
                        @CodePointLength(max = 500, message = "地点备注不能超过 500 个字符") String note) {
    }
}
