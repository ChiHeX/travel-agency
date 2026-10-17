package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Departure;

import java.time.LocalDateTime;

/**
 * 后台团期管理视图，对齐契约 {@code AdminDeparture}
 * （= {@code Departure} 的全部字段 + 乐观锁版本号 {@code version}）。
 *
 * <p>为什么不把 {@code version} 直接加进 {@link DepartureView}：那个视图同时被公开线路详情、
 * 导游端和订单详情复用，而那些场景既不会提交修改、也没有版本语义。
 * 契约里对它们的断言明确要求不得出现该字段，因此后台单开一个视图，
 * 只在 {@code /admin/departures} 一组端点上暴露版本号。</p>
 *
 * <p>字段映射复用 {@link DepartureView#from}，避免两处各写一遍联查字段与计算字段。</p>
 */
public record AdminDepartureView(
        Long id,
        Long routeId,
        String routeName,
        java.time.LocalDate startDate,
        java.time.LocalDate endDate,
        java.math.BigDecimal adultPrice,
        java.math.BigDecimal childPrice,
        Integer maxPeople,
        Integer reservedPeople,
        Integer confirmedPeople,
        Integer availableSeats,
        Long guideId,
        String guideName,
        String status,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AdminDepartureView from(Departure departure, String routeName, String guideName) {
        DepartureView view = DepartureView.from(departure, routeName, guideName);
        if (view == null) {
            return null;
        }
        return new AdminDepartureView(
                view.id(), view.routeId(), view.routeName(), view.startDate(), view.endDate(),
                view.adultPrice(), view.childPrice(), view.maxPeople(), view.reservedPeople(),
                view.confirmedPeople(), view.availableSeats(), view.guideId(), view.guideName(),
                view.status(), departure.version, view.createdAt(), view.updatedAt());
    }
}
