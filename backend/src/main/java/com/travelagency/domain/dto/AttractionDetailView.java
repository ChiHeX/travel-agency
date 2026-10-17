package com.travelagency.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 景点公开详情，对齐契约 {@code AttractionDetail}（必填 {@code attraction} 与 {@code departures}）。
 *
 * <p>{@code attraction} 直接复用 {@link AttractionView}（契约 {@code Attraction}）：
 * 这里此前另有一份字段完全相同的内部 record，两份映射对 {@code status}、坐标的处理方式
 * 各不相同，任何一处改动都可能让公开详情与后台列表对同一个景点给出不同结果。</p>
 */
public record AttractionDetailView(AttractionView attraction, List<Trip> departures) {

    /** 途经该景点、且有未来可报名团期的线路团次，对齐契约 {@code AttractionTrip}。 */
    public record Trip(Long id, Long routeId, String routeName, String departureCity,
                       LocalDate startDate, LocalDate endDate, BigDecimal adultPrice, BigDecimal childPrice,
                       int availableSeats) {
    }
}
