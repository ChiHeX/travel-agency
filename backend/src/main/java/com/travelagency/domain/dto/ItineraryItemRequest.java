package com.travelagency.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 行程项目新增/修改请求，对齐契约 {@code ItineraryItemRequest}（additionalProperties: false）。
 *
 * <p>不接受 {@code dayId}：所属每日行程由 URL 路径参数决定。</p>
 */
public record ItineraryItemRequest(
        @NotNull(message = "排序号不能为空")
        @Min(value = 1, message = "排序号不能小于 1") Integer sortNo,
        @NotBlank(message = "行程项目类型不能为空")
        @Pattern(regexp = "ATTRACTION|TRANSPORT|MEAL|ACTIVITY|OTHER",
                message = "行程项目类型只能是 ATTRACTION、TRANSPORT、MEAL、ACTIVITY 或 OTHER") String itemType,
        @NotBlank(message = "行程项目名称不能为空")
        @Size(max = 200, message = "行程项目名称不能超过 200 个字符") String name,
        @Size(max = 10000, message = "行程项目说明不能超过 10000 个字符") String description,
        Long attractionId,
        @DecimalMin(value = "-180", message = "经度应在 -180 到 180 之间")
        @DecimalMax(value = "180", message = "经度应在 -180 到 180 之间") Double longitude,
        @DecimalMin(value = "-90", message = "纬度应在 -90 到 90 之间")
        @DecimalMax(value = "90", message = "纬度应在 -90 到 90 之间") Double latitude) {
}
