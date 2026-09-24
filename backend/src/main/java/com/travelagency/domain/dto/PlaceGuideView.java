package com.travelagency.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PlaceGuideView(Long id, String title, String summary, String city, String destination,
                             String coverUrl, String status, Long authorId, String authorName,
                             LocalDateTime publishedAt, List<Place> places) {
    public record Place(Long attractionId, String name, String city, String address,
                        Double longitude, Double latitude, String intro,
                        String note, int sortOrder) {
    }
}
