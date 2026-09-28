package com.travelagency.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 景点新增/修改请求，对齐契约 {@code AttractionUpsertRequest}（{@code additionalProperties: false}，
 * 必填 {@code name/city/dataSource}）。
 *
 * <p>只声明契约允许客户端提交的字段：主键由 URL 路径参数决定，{@code createdAt} /
 * {@code updatedAt} 由数据库维护。实体被直接当作请求体时，客户端可以提交 {@code id}、
 * {@code createdAt} 等字段（早期 {@code AdminController} 的实现就是这样），
 * 越权字段现在会被全局 {@code FAIL_ON_UNKNOWN_PROPERTIES} 按契约的
 * {@code additionalProperties: false} 拒绝（400）。</p>
 *
 * <p>坐标的约束与 {@link ItineraryItemRequest} 保持一致（经度 ±180、纬度 ±90），
 * 与契约 {@code Longitude} / {@code Latitude} 的定义一一对应，超范围在写库前返回 422，
 * 而不是等到 {@code DECIMAL(10,7)} 落库时报错变成 500。</p>
 *
 * <p>{@code status} 是契约 {@code AccountStatus} 枚举，映射到库内 {@code status} 的 1/0。
 * 它在契约里<b>不是必填</b>：新增时缺省按 {@code ACTIVE} 建档，修改时缺省表示
 * "保持库内当前状态"，避免漏传字段就把一个已停用的景点悄悄重新启用。</p>
 */
public record AttractionUpsertRequest(
        @NotBlank(message = "景点名称不能为空")
        @Size(max = 128, message = "景点名称最多 128 个字符") String name,

        @NotBlank(message = "所属城市不能为空")
        @Size(max = 64, message = "所属城市最多 64 个字符") String city,

        @Size(max = 255, message = "景点地址最多 255 个字符") String address,

        @DecimalMin(value = "-180", message = "经度应在 -180 到 180 之间")
        @DecimalMax(value = "180", message = "经度应在 -180 到 180 之间") Double longitude,

        @DecimalMin(value = "-90", message = "纬度应在 -90 到 90 之间")
        @DecimalMax(value = "90", message = "纬度应在 -90 到 90 之间") Double latitude,

        @Size(max = 10000, message = "景点简介最多 10000 个字符") String intro,

        @NotBlank(message = "数据来源说明不能为空")
        @Size(max = 500, message = "数据来源说明最多 500 个字符") String dataSource,

        @Pattern(regexp = "ACTIVE|DISABLED", message = "景点状态只能是 ACTIVE 或 DISABLED") String status) {

    /**
     * 判断本次请求是否显式提交了状态。
     *
     * <p>{@code status} 为 {@code null} 表示客户端没有提交该字段（契约里它不是必填），
     * 修改时应当保留库内现值；空串由 {@code @Pattern} 拒绝，不会走到这里。</p>
     */
    public boolean hasStatus() {
        return status != null;
    }
}
