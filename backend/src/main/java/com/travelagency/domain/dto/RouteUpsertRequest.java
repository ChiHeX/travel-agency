package com.travelagency.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 线路新增/修改请求，对齐契约 {@code RouteUpsertRequest}（additionalProperties: false）。
 *
 * <p>只声明契约允许客户端提交的字段：{@code status}、评分、报名人次、{@code createdBy}
 * 等由服务端决定，客户端提交这些字段会因未知字段被拒绝（400），从而不能绕过上架流程。</p>
 */
public record RouteUpsertRequest(
        @NotBlank(message = "线路名称不能为空")
        @Size(min = 2, max = 200, message = "线路名称长度应为 2-200 个字符") String name,
        @NotBlank(message = "出发城市不能为空")
        @Size(max = 64, message = "出发城市不能超过 64 个字符") String departureCity,
        @NotBlank(message = "目的地不能为空")
        @Size(max = 255, message = "目的地不能超过 255 个字符") String destination,
        @NotNull(message = "行程天数不能为空")
        @Min(value = 1, message = "行程天数不能小于 1 天")
        @Max(value = 365, message = "行程天数不能超过 365 天") Integer durationDays,
        @Size(max = 10000, message = "线路简介不能超过 10000 个字符") String description,
        @Size(max = 500, message = "封面地址不能超过 500 个字符") String coverUrl,
        @Size(max = 10000, message = "费用包含不能超过 10000 个字符") String included,
        @Size(max = 10000, message = "费用不含不能超过 10000 个字符") String excluded,
        @Size(max = 10000, message = "报名须知不能超过 10000 个字符") String bookingNotice) {
}
