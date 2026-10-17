package com.travelagency.domain.dto;

import com.travelagency.common.validation.CoordinatePairComplete;
import com.travelagency.common.validation.HasCoordinatePair;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 行程项目新增/修改请求，对齐契约 {@code ItineraryItemRequest}（additionalProperties: false）。
 *
 * <p>不接受 {@code dayId}：所属每日行程由 URL 路径参数决定。</p>
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是出现「契约允许、实现却回 422」。
 * 库内列宽同样是码点口径（{@code VARCHAR(200)} 在 utf8mb4 下就是 200 个字符），两边一致。</p>
 *
 * <p><b>经纬度必须成对</b>（{@link CoordinatePairComplete}，对应契约的
 * {@code CoordinatePairRule}）：只填一个的坐标在用户端地图上无法落点。两个都留空是合法的，
 * 此时行程项会<b>整对</b>继承所关联景点的坐标（见 {@code AdminRouteService}）；景点坐标
 * 不成对（历史数据）时不继承。</p>
 */
@CoordinatePairComplete
public record ItineraryItemRequest(
        @NotNull(message = "排序号不能为空")
        @Min(value = 1, message = "排序号不能小于 1") Integer sortNo,
        @NotBlank(message = "行程项目类型不能为空")
        @Pattern(regexp = "ATTRACTION|TRANSPORT|MEAL|ACTIVITY|OTHER",
                message = "行程项目类型只能是 ATTRACTION、TRANSPORT、MEAL、ACTIVITY 或 OTHER") String itemType,
        @NotBlank(message = "行程项目名称不能为空")
        @CodePointLength(max = 200, message = "行程项目名称不能超过 200 个字符") String name,
        @CodePointLength(max = 10000, message = "行程项目说明不能超过 10000 个字符") String description,
        Long attractionId,
        @DecimalMin(value = "-180", message = "经度应在 -180 到 180 之间")
        @DecimalMax(value = "180", message = "经度应在 -180 到 180 之间") Double longitude,
        @DecimalMin(value = "-90", message = "纬度应在 -90 到 90 之间")
        @DecimalMax(value = "90", message = "纬度应在 -90 到 90 之间") Double latitude)
        implements HasCoordinatePair {
}
