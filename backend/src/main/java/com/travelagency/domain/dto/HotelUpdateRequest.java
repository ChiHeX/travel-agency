package com.travelagency.domain.dto;

import com.travelagency.common.validation.CoordinatePairComplete;
import com.travelagency.common.validation.HasCoordinatePair;
import com.travelagency.common.validation.HotelProfileRules;
import com.travelagency.common.validation.UniqueElements;
import com.travelagency.common.enums.HotelFacility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

import java.util.List;

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
 *
 * <p><b>整体替换与清空规则</b>：{@code PUT} 提交的是完整资料，未提交或提交 {@code null}
 * 的可空字段（{@code address}、{@code contactPhone}、{@code coverUrl}、{@code starRating}、
 * {@code checkInTime}、{@code checkOutTime}、{@code longitude}、{@code latitude}、
 * {@code intro}）会被清成 {@code null}；{@code images} / {@code facilities} 省略或提交空集合
 * 会清空对应记录。否则"删掉一段旧资料"就没有明确写法，只能靠调用方猜 ——
 * 这正是 {@code HotelService#update} 显式列出每一列、而不是回写整个实体的原因。</p>
 *
 * <p><b>经纬度必须成对</b>（{@link CoordinatePairComplete}，对应契约的
 * {@code CoordinatePairRule}）：与建档请求同口径。</p>
 */
@CoordinatePairComplete
public record HotelUpdateRequest(
        @NotBlank(message = "酒店名称不能为空")
        @CodePointLength(max = 128, message = "酒店名称最多 128 个字符") String name,

        @NotBlank(message = "酒店城市不能为空")
        @CodePointLength(max = HotelProfileRules.CITY_MAX_CHARS,
                message = HotelProfileRules.CITY_LENGTH_MESSAGE) String city,

        @CodePointLength(max = 255, message = "酒店地址最多 255 个字符") String address,

        @CodePointLength(max = 20, message = "联系电话最多 20 个字符") String contactPhone,

        @CodePointLength(max = HotelProfileRules.IMAGE_URL_MAX_CHARS,
                message = HotelProfileRules.IMAGE_URL_LENGTH_MESSAGE)
        @Pattern(regexp = HotelProfileRules.IMAGE_URL_PATTERN,
                message = HotelProfileRules.IMAGE_URL_MESSAGE) String coverUrl,

        @Size(max = HotelProfileRules.MAX_IMAGES, message = HotelProfileRules.IMAGE_COUNT_MESSAGE)
        // 元素必须非空：JSON 里的 null 元素会绕过 @Valid（级联校验对 null 直接放行），
        // 直到装配实体取 url 时才 NPE 变成 500。
        List<@NotNull(message = "图片不能为空") @Valid HotelImageRequest> images,

        @Min(value = 1, message = "官方星级只能是 1 到 5")
        @Max(value = 5, message = "官方星级只能是 1 到 5") Integer starRating,

        // 对应契约的 uniqueItems: true：重复取值作为字段级错误进 errors[]（field=facilities）
        @UniqueElements
        List<@Pattern(regexp = HotelFacility.PATTERN, message = HotelFacility.MESSAGE) String> facilities,

        @Pattern(regexp = HotelProfileRules.CLOCK_TIME_PATTERN,
                message = HotelProfileRules.CHECK_IN_TIME_MESSAGE) String checkInTime,

        @Pattern(regexp = HotelProfileRules.CLOCK_TIME_PATTERN,
                message = HotelProfileRules.CHECK_OUT_TIME_MESSAGE) String checkOutTime,

        @DecimalMin(value = "-180", message = "经度应在 -180 到 180 之间")
        @DecimalMax(value = "180", message = "经度应在 -180 到 180 之间") Double longitude,

        @DecimalMin(value = "-90", message = "纬度应在 -90 到 90 之间")
        @DecimalMax(value = "90", message = "纬度应在 -90 到 90 之间") Double latitude,

        @CodePointLength(max = 10000, message = "酒店简介最多 10000 个字符") String intro,

        @NotBlank(message = "数据来源说明不能为空")
        @CodePointLength(max = 500, message = "数据来源说明最多 500 个字符") String dataSource,

        @Pattern(regexp = "ACTIVE|DISABLED", message = "酒店状态只能是 ACTIVE 或 DISABLED") String status,

        @NotNull(message = "酒店版本号不能为空")
        @Min(value = 0, message = "酒店版本号不能为负数") Integer version)
        implements HasCoordinatePair {

    /** 可编辑字段部分，供服务层复用建档请求的同一套赋值与校验口径。 */
    public HotelCreateRequest editableFields() {
        return new HotelCreateRequest(name, city, address, contactPhone, coverUrl, images, starRating,
                facilities, checkInTime, checkOutTime, longitude, latitude, intro, dataSource, status);
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
