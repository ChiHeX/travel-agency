package com.travelagency.domain.dto;

import com.travelagency.common.enums.AccountStatus;
import com.travelagency.domain.entity.Hotel;

public record HotelSummaryView(
        Long id,
        String name,
        String city,
        String address,
        String coverUrl,
        Integer starRating) {

    // 停用酒店保留行程名称，但不提供公开详情入口。
    public static HotelSummaryView forItinerary(Hotel hotel) {
        if (hotel == null || !AccountStatus.ACTIVE.equals(AccountStatus.of(hotel.status))) {
            return null;
        }
        return new HotelSummaryView(hotel.id, hotel.name, hotel.city, hotel.address,
                hotel.coverUrl, hotel.starRating);
    }
}
