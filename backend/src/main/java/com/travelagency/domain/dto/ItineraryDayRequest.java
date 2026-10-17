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

    public String effectiveAccommodationType() {
        return AccommodationType.isValid(accommodationType)
                ? accommodationType
                : AccommodationType.infer(hotelId);
    }
}
