package com.travelagency.domain.dto;

import com.travelagency.domain.entity.OrderTraveler;
import com.travelagency.domain.service.OrderService;

import java.time.LocalDate;

/**
 * 订单内出行人快照，对齐契约 OrderTraveler。
 * 与常用出行人 TravelerView 的差别：本视图要求 travelerType（ADULT/CHILD），且不返回 createdAt/updatedAt。
 */
public record OrderTravelerView(
        Long id,
        String travelerType,
        String name,
        String gender,
        LocalDate birthDate,
        String idType,
        String idNoMasked,
        String phone,
        String emergencyName,
        String emergencyPhone) {

    public static OrderTravelerView from(OrderTraveler snapshot) {
        return new OrderTravelerView(
                snapshot.id,
                snapshot.travelerType,
                snapshot.name,
                snapshot.gender,
                snapshot.birthDate,
                snapshot.idType,
                OrderService.maskId(snapshot.idNo),
                snapshot.phone,
                snapshot.emergencyName,
                snapshot.emergencyPhone);
    }
}
