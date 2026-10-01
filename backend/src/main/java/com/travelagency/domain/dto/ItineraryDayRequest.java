package com.travelagency.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 每日行程新增/修改请求，对齐契约 {@code ItineraryDayRequest}（additionalProperties: false）。
 *
 * <p>不接受 {@code routeId}：线路归属由 URL 路径参数决定，避免把某一天挂到其它线路上。</p>
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是出现「契约允许、实现却回 422」。
 * 库内列宽同样是码点口径（{@code VARCHAR(200)} 在 utf8mb4 下就是 200 个字符），两边一致。</p>
 */
public record ItineraryDayRequest(
        @NotNull(message = "行程天数序号不能为空")
        @Min(value = 1, message = "行程天数序号不能小于 1") Integer dayNumber,
        @NotBlank(message = "当日行程标题不能为空")
        @CodePointLength(max = 200, message = "当日行程标题不能超过 200 个字符") String title,
        @CodePointLength(max = 10000, message = "当日行程说明不能超过 10000 个字符") String description,
        @CodePointLength(max = 255, message = "交通说明不能超过 255 个字符") String transportation,
        @CodePointLength(max = 255, message = "餐食说明不能超过 255 个字符") String meals,
        Long hotelId) {
}
