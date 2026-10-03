package com.travelagency.domain.dto;

import com.travelagency.domain.entity.HotelImage;

/**
 * 酒店图片视图，对齐契约 {@code HotelImage}（{@code additionalProperties: false}，
 * 必填 {@code url/sortOrder}）。
 *
 * <p>不返回 {@code hotel_image.id}：契约没有这个字段，调用方也用不到 ——
 * 图片是随酒店整体提交（{@code PUT /admin/hotels/{hotelId}} 替换整个集合）的，
 * 不存在"按图片主键单独修改某一张"的用法，暴露主键只会让调用方以为可以按 id 引用图片。</p>
 */
public record HotelImageView(String url, String alt, Integer sortOrder) {

    public static HotelImageView from(HotelImage image) {
        if (image == null) {
            return null;
        }
        return new HotelImageView(image.url, image.alt, image.sortOrder);
    }
}
