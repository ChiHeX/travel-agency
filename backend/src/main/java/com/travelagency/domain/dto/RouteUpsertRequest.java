package com.travelagency.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 线路新增/修改请求，对齐契约 {@code RouteUpsertRequest}（additionalProperties: false）。
 *
 * <p>只声明契约允许客户端提交的字段：{@code status}、评分、报名人次、{@code createdBy}
 * 等由服务端决定，客户端提交这些字段会因未知字段被拒绝（400），从而不能绕过上架流程。</p>
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是出现「契约允许、实现却回 422」（200 个 emoji 的线路名，
 * 码点 200 ≤ 200 合法，码元却是 400）。库内列宽同样是码点口径
 * （{@code VARCHAR(200)} 在 utf8mb4 下就是 200 个字符），三边一致。</p>
 */
public record RouteUpsertRequest(
        @NotBlank(message = "线路名称不能为空")
        @CodePointLength(min = 2, max = 200, message = "线路名称长度应为 2-200 个字符") String name,
        @NotBlank(message = "出发城市不能为空")
        @CodePointLength(max = 64, message = "出发城市不能超过 64 个字符") String departureCity,
        @NotBlank(message = "目的地不能为空")
        @CodePointLength(max = 255, message = "目的地不能超过 255 个字符") String destination,
        @NotNull(message = "行程天数不能为空")
        @Min(value = 1, message = "行程天数不能小于 1 天")
        @Max(value = 365, message = "行程天数不能超过 365 天") Integer durationDays,
        @CodePointLength(max = 10000, message = "线路简介不能超过 10000 个字符") String description,
        @CodePointLength(max = 500, message = "封面地址不能超过 500 个字符") String coverUrl,
        @CodePointLength(max = 10000, message = "费用包含不能超过 10000 个字符") String included,
        @CodePointLength(max = 10000, message = "费用不含不能超过 10000 个字符") String excluded,
        @CodePointLength(max = 10000, message = "报名须知不能超过 10000 个字符") String bookingNotice) {
}
