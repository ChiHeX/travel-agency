package com.travelagency.domain.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 常用出行人，对齐契约 Traveler（required 含 createdAt / updatedAt）。
 */
public record TravelerView(
        Long id,
        String name,
        String gender,
        LocalDate birthDate,
        String idType,
        String idNoMasked,
        String phone,
        String emergencyName,
        String emergencyPhone,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
