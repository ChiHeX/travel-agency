package com.travelagency.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 酒店新增/修改请求，对齐契约 {@code HotelUpsertRequest}（{@code additionalProperties: false}，
 * 必填 {@code name/dataSource}）。
 *
 * <p>只声明契约允许客户端提交的字段：主键由 URL 路径参数决定，{@code createdAt} /
 * {@code updatedAt} 由数据库维护。此前 {@code AdminController} 直接把 {@code Hotel} 实体当作请求体，
 * 客户端可以提交 {@code id}、{@code createdAt} 等契约外字段并指定主键与审计时间；
 * 改成独立 DTO 后这些字段会被全局 {@code FAIL_ON_UNKNOWN_PROPERTIES} 按契约的
 * {@code additionalProperties: false} 拒绝（400）。</p>
 *
 * <p>酒店资料不含 {@code city}：契约 {@code Hotel} 只有 {@code name/address/contactPhone}，
 * 城市信息属于景点（{@code Attraction}）与地点指南（{@code PlaceGuide}）的口径，
 * 不要为了对齐界面而给酒店加上契约里不存在的字段。</p>
 *
 * <p>坐标的约束与 {@link AttractionUpsertRequest}、{@link ItineraryItemRequest} 保持一致
 * （经度 ±180、纬度 ±90），与契约 {@code Longitude} / {@code Latitude} 一一对应：
 * 超范围在写库前返回 422，而不是等到 {@code DECIMAL(10,7)} 落库时报错变成 500。</p>
 *
 * <p>{@code status} 是契约 {@code AccountStatus} 枚举，映射到库内 {@code status} 的 1/0。
 * 它在契约里<b>不是必填</b>：新增时缺省按 {@code ACTIVE} 建档，修改时缺省表示
 * "保持库内当前状态"，避免漏传字段就把一家已停用的酒店悄悄重新启用。</p>
 */
public record HotelUpsertRequest(
        @NotBlank(message = "酒店名称不能为空")
        @Size(max = 128, message = "酒店名称最多 128 个字符") String name,

        @Size(max = 255, message = "酒店地址最多 255 个字符") String address,

        @Size(max = 20, message = "联系电话最多 20 个字符") String contactPhone,

        @DecimalMin(value = "-180", message = "经度应在 -180 到 180 之间")
        @DecimalMax(value = "180", message = "经度应在 -180 到 180 之间") Double longitude,

        @DecimalMin(value = "-90", message = "纬度应在 -90 到 90 之间")
        @DecimalMax(value = "90", message = "纬度应在 -90 到 90 之间") Double latitude,

        @Size(max = 10000, message = "酒店简介最多 10000 个字符") String intro,

        @NotBlank(message = "数据来源说明不能为空")
        @Size(max = 500, message = "数据来源说明最多 500 个字符") String dataSource,

        @Pattern(regexp = "ACTIVE|DISABLED", message = "酒店状态只能是 ACTIVE 或 DISABLED") String status) {

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
