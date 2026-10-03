package com.travelagency.domain.dto;

import com.travelagency.common.enums.AccountStatus;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.HotelImage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record HotelView(
        Long id,
        String name,
        String city,
        String address,
        String contactPhone,
        String coverUrl,
        List<HotelImageView> images,
        Integer starRating,
        List<String> facilities,
        String checkInTime,
        String checkOutTime,
        Double longitude,
        Double latitude,
        String intro,
        String dataSource,
        String status,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static HotelView from(Hotel hotel, List<HotelImage> images, List<String> facilities) {
        if (hotel == null) {
            return null;
        }
        return new HotelView(hotel.id, hotel.name, hotel.city, hotel.address, hotel.contactPhone,
                hotel.coverUrl,
                images == null ? List.of() : images.stream().map(HotelImageView::from).toList(),
                hotel.starRating,
                facilities == null ? List.of() : List.copyOf(facilities),
                hotel.checkInTime, hotel.checkOutTime,
                number(hotel.longitude), number(hotel.latitude), hotel.intro,
                hotel.dataSource, AccountStatus.of(hotel.status), hotel.version,
                hotel.createdAt, hotel.updatedAt);
    }

    /** 库内 1/0 → 契约 {@code AccountStatus}；再转 JSON number，绕开 BigDecimal 的两位小数序列化器。 */
    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
