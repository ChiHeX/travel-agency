package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Attraction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AttractionDetailView(Place attraction, List<Trip> departures) {
    public record Place(Long id, String name, String city, String address, Double longitude,
                        Double latitude, String intro, String dataSource, String status,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static Place from(Attraction attraction) {
            return new Place(attraction.id, attraction.name, attraction.city, attraction.address,
                    attraction.longitude == null ? null : attraction.longitude.doubleValue(),
                    attraction.latitude == null ? null : attraction.latitude.doubleValue(),
                    attraction.intro, attraction.dataSource, "ACTIVE",
                    attraction.createdAt, attraction.updatedAt);
        }
    }

    public record Trip(Long id, Long routeId, String routeName, String departureCity,
                       LocalDate startDate, LocalDate endDate, BigDecimal adultPrice, BigDecimal childPrice,
                       int availableSeats) {
    }
}
