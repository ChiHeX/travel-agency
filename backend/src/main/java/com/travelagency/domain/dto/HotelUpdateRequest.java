package com.travelagency.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 酒店资料修改请求，对齐契约 {@code HotelUpdateRequest}（{@code additionalProperties: false}）。
 *
 * <p>字段与 {@link HotelCreateRequest} 相同，额外要求回传<b>读取时拿到的</b>
 * {@code version}：后端执行 {@code UPDATE ... WHERE id = ? AND version = ?}，
 * 成功后把版本加一；版本不一致说明这份资料已被他人修改，返回
 * {@code 409 HOTEL_VERSION_CONFLICT}，本次修改不生效。</p>
 *
 * <p>解决的是"两位工作人员各自打开同一条酒店资料，先后保存，后保存的人无意覆盖了前一位的
 * 地址 / 联系电话 / 状态改动"（丢失更新）。契约把 {@code version} 列为必填，
 * 缺字段按 422 处理而不是静默当成 0 —— 静默按 0 会把"忘了回传版本"变成一次必然的冲突或
 * 一次意外的覆盖。</p>
 */
public record HotelUpdateRequest(
        @NotBlank(message = "酒店名称不能为空")
        @CodePointLength(max = 128, message = "酒店名称最多 128 个字符") String name,

        @CodePointLength(max = 255, message = "酒店地址最多 255 个字符") String address,

        @CodePointLength(max = 20, message = "联系电话最多 20 个字符") String contactPhone,

        @DecimalMin(value = "-180", message = "经度应在 -180 到 180 之间")
        @DecimalMax(value = "180", message = "经度应在 -180 到 180 之间") Double longitude,

        @DecimalMin(value = "-90", message = "纬度应在 -90 到 90 之间")
        @DecimalMax(value = "90", message = "纬度应在 -90 到 90 之间") Double latitude,

        @CodePointLength(max = 10000, message = "酒店简介最多 10000 个字符") String intro,

        @NotBlank(message = "数据来源说明不能为空")
        @CodePointLength(max = 500, message = "数据来源说明最多 500 个字符") String dataSource,

        @Pattern(regexp = "ACTIVE|DISABLED", message = "酒店状态只能是 ACTIVE 或 DISABLED") String status,

        @NotNull(message = "酒店版本号不能为空")
        @Min(value = 0, message = "酒店版本号不能为负数") Integer version) {

    /** 可编辑字段部分，供服务层复用建档请求的同一套赋值与校验口径。 */
    public HotelCreateRequest editableFields() {
        return new HotelCreateRequest(name, address, contactPhone, longitude, latitude,
                intro, dataSource, status);
    }

    /**
     * 判断本次请求是否显式提交了状态。
     *
     * <p>{@code status} 为 {@code null} 表示客户端没有提交该字段（契约里它不是必填），
     * 修改时应当保留库内现值；空串由 {@code @Pattern} 拒绝，不会走到这里。
     * 前端只在用户真的改动状态后才提交它，因此这里的 {@code null} 也是
     * "不要把旧状态写回去"的信号。</p>
     */
    public boolean hasStatus() {
        return status != null;
    }
}
