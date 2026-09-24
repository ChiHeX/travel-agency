package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Departure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 团期对外视图，对齐契约 Departure（additionalProperties: false）。
 *
 * <p>不直接序列化持久化实体：实体带 version 字段（契约无此项），
 * 且缺少 availableSeats 这一计算字段与 routeName / guideName 这两个联查字段。</p>
 */
public record DepartureView(
        Long id,
        Long routeId,
        String routeName,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal adultPrice,
        BigDecimal childPrice,
        Integer maxPeople,
        Integer reservedPeople,
        Integer confirmedPeople,
        Integer availableSeats,
        Long guideId,
        String guideName,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static DepartureView from(Departure departure, String routeName, String guideName) {
        if (departure == null) {
            return null;
        }
        return new DepartureView(
                departure.id,
                departure.routeId,
                routeName,
                departure.startDate,
                departure.endDate,
                departure.adultPrice,
                departure.childPrice,
                departure.maxPeople,
                departure.reservedPeople,
                departure.confirmedPeople,
                availableSeats(departure),
                departure.guideId,
                guideName,
                departure.status,
                departure.createdAt,
                departure.updatedAt);
    }

    /** 剩余名额 = 最大人数 - 已预留 - 已确认，下限为 0。 */
    public static int availableSeats(Departure departure) {
        int max = valueOrZero(departure.maxPeople);
        int reserved = valueOrZero(departure.reservedPeople);
        int confirmed = valueOrZero(departure.confirmedPeople);
        return Math.max(max - reserved - confirmed, 0);
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
