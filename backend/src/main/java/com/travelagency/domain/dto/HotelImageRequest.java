package com.travelagency.domain.dto;

import com.travelagency.common.validation.HotelProfileRules;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 酒店图片的提交模型，对应契约 {@code HotelImageUpsert}。
 *
 * <p>{@code url} 必须是非空的 http / https 绝对地址：图片会渲染在无需登录的用户端页面上，
 * 而第一版由后台直接填写外部地址，服务端没有其它机会再校验它。</p>
 *
 * <p>{@code sortOrder} 决定展示顺序（升序，最小 1）；同一酒店内允许重复，顺序相同时按提交顺序排列，
 * 因此这里不要求它唯一 —— 强行要求唯一会让"批量替换图片"在重排顺序时多出一堆无意义的冲突。</p>
 */
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
