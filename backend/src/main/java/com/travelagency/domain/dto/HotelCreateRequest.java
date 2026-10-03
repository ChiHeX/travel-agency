package com.travelagency.domain.dto;

import com.travelagency.common.enums.HotelFacility;
import com.travelagency.common.validation.CoordinatePairComplete;
import com.travelagency.common.validation.HasCoordinatePair;
import com.travelagency.common.validation.HotelProfileRules;
import com.travelagency.common.validation.UniqueElements;
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
 * 酒店建档请求，对齐契约 {@code HotelCreateRequest}（{@code additionalProperties: false}，
 * 必填 {@code name/city/dataSource}）。
 *
 * <p>只声明契约允许客户端提交的字段：主键由服务端生成，{@code createdAt} / {@code updatedAt}
 * 由数据库维护，{@code version} 由服务端从 0 起算。此前 {@code AdminController} 直接把
 * {@code Hotel} 实体当作请求体，客户端可以提交 {@code id}、{@code createdAt} 等契约外字段
 * 并指定主键与审计时间；改成独立 DTO 后这些字段会被全局 {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * 按契约的 {@code additionalProperties: false} 拒绝（400）。</p>
 *
 * <p>修改走 {@link HotelUpdateRequest}（比本文多一个必填的 {@code version}），
 * 两者刻意分开定义：创建也要传版本号会变成语义不清的契约（版本由服务端决定）。</p>
 *
 * <p>{@code city} 是必填项（契约 {@code minLength: 1, maxLength: 64}）：它既用于用户端酒店卡片，
 * 也是后台列表的筛选维度，留空等于这条资料无法被城市检索到。注意这是相对旧契约的
 * <b>破坏性变更</b>，已在 {@code docs/API.md} §12.2 登记；存量数据的 {@code city} 由迁移脚本
 * 补成空串（表示尚未录入），后台补录后即可正常筛选。</p>
 *
 * <p>坐标的约束与 {@link AttractionUpsertRequest}、{@link ItineraryItemRequest} 保持一致
 * （经度 ±180、纬度 ±90），与契约 {@code Longitude} / {@code Latitude} 一一对应：
 * 超范围在写库前返回 422，而不是等到 {@code DECIMAL(10,7)} 落库时报错变成 500。</p>
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@code @CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是"契约允许、实现却回 422"（例如 100 个 emoji 的酒店名，
 * 码点 100 ≤ 128 合法，码元却是 200）。这条规则与 {@code KeywordRules}、
 * {@code AttractionController} 的 {@code city} 参数同口径。
 * 库内列宽按字符定义（{@code VARCHAR(128)} 在 utf8mb4 下同样按码点计），两边一致。</p>
 *
 * <p>{@code status} 是契约 {@code AccountStatus} 枚举，映射到库内 {@code status} 的 1/0。
 * 它在契约里<b>不是必填</b>：新增时缺省按 {@code ACTIVE} 建档，修改时缺省表示
 * "保持库内当前状态"，避免漏传字段就把一家已停用的酒店悄悄重新启用。</p>
 *
 * <p><b>经纬度必须成对</b>（{@link CoordinatePairComplete}，对应契约的
 * {@code CoordinatePairRule}）：只填一个的坐标在用户端地图上无法落点，会被静默丢弃。</p>
 */
@CoordinatePairComplete
public record HotelCreateRequest(
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

        // 设施标签：元素级 @Pattern 直接作用在集合元素上（不需要级联校验），空串与枚举外的取值都会被拒；
        // @UniqueElements 对应契约的 uniqueItems: true，重复取值作为字段级错误进 errors[]（field=facilities），
        // 服务层 HotelFacility.normalize 仍保留同一份判定作为兜底。
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

        @Pattern(regexp = "ACTIVE|DISABLED", message = "酒店状态只能是 ACTIVE 或 DISABLED") String status)
        implements HasCoordinatePair {

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
