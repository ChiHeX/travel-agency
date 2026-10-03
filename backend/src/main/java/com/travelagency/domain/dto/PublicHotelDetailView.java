package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.HotelImage;

import java.math.BigDecimal;
import java.util.List;

public record PublicHotelDetailView(
        Long id,
        String name,
        String city,
        String address,
        String coverUrl,
        List<HotelImageView> images,
        Integer starRating,
        String intro,
        List<String> facilities,
        String checkInTime,
        String checkOutTime,
        Double longitude,
        Double latitude,
        String dataSource) {

    public static PublicHotelDetailView from(Hotel hotel, List<HotelImage> images, List<String> facilities) {
        if (hotel == null) {
            return null;
        }
        return new PublicHotelDetailView(hotel.id, hotel.name, hotel.city, hotel.address, hotel.coverUrl,
                images == null ? List.of() : images.stream().map(HotelImageView::from).toList(),
                hotel.starRating, hotel.intro,
                facilities == null ? List.of() : List.copyOf(facilities),
                hotel.checkInTime, hotel.checkOutTime,
                number(hotel.longitude), number(hotel.latitude), hotel.dataSource);
    }

    /** 坐标转为 JSON number，避免全局 BigDecimal 序列化为金额字符串。 */
    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
