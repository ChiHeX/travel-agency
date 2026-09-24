package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuideUpdateRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(min = 3, max = 20) String phone,
        @Size(max = 1000) String intro) {
}
