package com.travelagency.domain.dto;

import com.travelagency.domain.entity.HotelImage;

public record HotelImageView(String url, String alt, Integer sortOrder) {

    public static HotelImageView from(HotelImage image) {
        if (image == null) {
            return null;
        }
        return new HotelImageView(image.url, image.alt, image.sortOrder);
    }
}
