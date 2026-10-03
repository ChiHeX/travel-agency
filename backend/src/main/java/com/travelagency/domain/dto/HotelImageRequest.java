package com.travelagency.domain.dto;

import com.travelagency.common.validation.HotelProfileRules;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record HotelImageRequest(
        @NotBlank(message = "图片地址不能为空")
        @CodePointLength(max = HotelProfileRules.IMAGE_URL_MAX_CHARS,
                message = HotelProfileRules.IMAGE_URL_LENGTH_MESSAGE)
        @Pattern(regexp = HotelProfileRules.IMAGE_URL_PATTERN,
                message = HotelProfileRules.IMAGE_URL_MESSAGE) String url,

        @CodePointLength(max = HotelProfileRules.IMAGE_ALT_MAX_CHARS,
                message = HotelProfileRules.IMAGE_ALT_LENGTH_MESSAGE) String alt,

        @NotNull(message = "图片排序号不能为空")
        @Min(value = 1, message = "图片排序号不能小于 1") Integer sortOrder) {
}
