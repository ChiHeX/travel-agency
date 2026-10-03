package com.travelagency.domain.dto;

import com.travelagency.common.enums.AccommodationType;
import com.travelagency.common.validation.AccommodationConsistent;
import com.travelagency.common.validation.HasAccommodationArrangement;
import com.travelagency.common.validation.HotelProfileRules;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 *
 * <p><b>住宿安排</b>（{@link AccommodationConsistent}）：{@code accommodationType} 与
 * {@code hotelId}、{@code accommodationStandard} 必须自洽 ——
 * {@code HOTEL} 必须关联酒店；{@code STANDARD} 必须写住宿标准且不关联酒店；
 * {@code NONE} / {@code PENDING} 不得关联酒店。类型未提交时按 {@code hotelId} 推断
 * （非空 → {@code HOTEL}，为空 → {@code PENDING}），与存量数据的迁移规则一致，
 * <b>推断永远不会得出 {@code NONE}</b>："没填酒店"不等于"当天不含住宿"。</p>
 *
 * <p>请求体是整体替换：未提交的可空字段会被清成 {@code null}，因此
 * {@code breakfastIncluded} 的 {@code false} 和"未提交"是两种不同结果，必须显式提交。</p>
 */
@AccommodationConsistent
public record ItineraryDayRequest(
        @NotNull(message = "行程天数序号不能为空")
        @Min(value = 1, message = "行程天数序号不能小于 1") Integer dayNumber,
        @NotBlank(message = "当日行程标题不能为空")
        @CodePointLength(max = 200, message = "当日行程标题不能超过 200 个字符") String title,
        @CodePointLength(max = 10000, message = "当日行程说明不能超过 10000 个字符") String description,
        @CodePointLength(max = 255, message = "交通说明不能超过 255 个字符") String transportation,
        @CodePointLength(max = 255, message = "餐食说明不能超过 255 个字符") String meals,
        @Pattern(regexp = AccommodationType.PATTERN, message = AccommodationType.MESSAGE) String accommodationType,
        @CodePointLength(max = HotelProfileRules.ACCOMMODATION_STANDARD_MAX_CHARS,
                message = HotelProfileRules.ACCOMMODATION_STANDARD_LENGTH_MESSAGE) String accommodationStandard,
        @CodePointLength(max = HotelProfileRules.ROOM_TYPE_MAX_CHARS,
                message = HotelProfileRules.ROOM_TYPE_LENGTH_MESSAGE) String roomType,
        Boolean breakfastIncluded,
        @CodePointLength(max = HotelProfileRules.ACCOMMODATION_NOTE_MAX_CHARS,
                message = HotelProfileRules.ACCOMMODATION_NOTE_LENGTH_MESSAGE) String accommodationNote,
        Long hotelId) implements HasAccommodationArrangement {

    /**
     * 本次请求实际生效的住宿类型：提交了就用提交的，没提交则按 {@code hotelId} 推断。
     *
     * <p>服务层与校验器都走这一个入口，避免"校验时按一套口径推断、落库时按另一套"——
     * 两边只要有一处不同，就会出现"校验通过但存下来的类型与酒店不一致"的行。</p>
     */
    public String effectiveAccommodationType() {
        return AccommodationType.isValid(accommodationType)
                ? accommodationType
                : AccommodationType.infer(hotelId);
    }
}
