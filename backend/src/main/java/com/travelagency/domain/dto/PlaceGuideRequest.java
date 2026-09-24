package com.travelagency.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PlaceGuideRequest(
        @NotBlank @Size(min = 2, max = 200) String title,
        @Size(max = 500) String summary,
        @NotBlank @Size(max = 64) String city,
        @Size(max = 128) String destination,
        @Size(max = 500) String coverUrl,
        @NotEmpty @Size(min = 2, max = 50) List<@Valid Place> places) {
    public record Place(@NotNull Long attractionId, @Size(max = 500) String note) {
    }
}
