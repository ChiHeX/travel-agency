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
 *
 * <p>后台团期管理需要额外的乐观锁版本号，见 {@link AdminDepartureView} ——
 * 那个字段只对会提交修改的后台编辑器有意义，不应出现在公开线路详情、
 * 导游端与订单详情共用的本视图里。</p>
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
        return Math.max(valueOrZero(departure.maxPeople) - occupiedSeats(departure), 0);
    }

    /**
     * 已占用名额 = 已预留（待支付 / 待确认）+ 已确认。
     *
     * <p>与 {@link #availableSeats} 共用同一口径：后台缩减 {@code maxPeople} 时要用它校验
     * "最大人数不得小于已占用名额"，否则可用名额会被钳到 0，把实际超卖藏在接口背后。</p>
     */
    public static int occupiedSeats(Departure departure) {
        return valueOrZero(departure.reservedPeople) + valueOrZero(departure.confirmedPeople);
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
