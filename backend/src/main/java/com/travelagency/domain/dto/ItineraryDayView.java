package com.travelagency.domain.dto;

import java.util.List;

public record ItineraryDayView(
        Long id,
        Long routeId,
        Integer dayNumber,
        String title,
        String description,
        String transportation,
        String meals,
        String accommodationType,
        String accommodationStandard,
        String roomType,
        Boolean breakfastIncluded,
        String accommodationNote,
        Long hotelId,
        String hotelName,
        HotelSummaryView hotel,
        List<ItineraryItemView> items) {
}
