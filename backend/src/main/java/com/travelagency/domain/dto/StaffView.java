package com.travelagency.domain.dto;

import com.travelagency.common.enums.AccountStatus;
import com.travelagency.domain.entity.Staff;
import com.travelagency.domain.entity.SysUser;

import java.time.LocalDateTime;

/**
 * 工作人员对外视图，对齐契约 Staff（additionalProperties: false）。
 *
 * <p>契约把 {@code username}、{@code realName}、{@code status} 列为 required，而这三项
 * 都不在 {@code staff} 表里：staff 只存 user_id/employee_no/department/position，
 * 账号名、姓名与启用状态都在 {@code sys_user} 上，且 {@code staff} 表没有状态列。
 * 直出实体会同时违反「缺少 required 字段」和「不得直接暴露 Entity」两条约定
 * （docs/API.md §14）。因此这里拍平映射，由调用方批量查回账号避免 N+1。</p>
 */
public record StaffView(
        Long id,
        Long userId,
        String username,
        String realName,
        String phone,
        String employeeNo,
        String department,
        String position,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static StaffView from(Staff staff, SysUser account) {
        if (staff == null) {
            return null;
        }
        return new StaffView(
                staff.id,
                staff.userId,
                account == null ? null : account.username,
                account == null ? null : account.realName,
                account == null ? null : account.phone,
                staff.employeeNo,
                staff.department,
                staff.position,
                AccountStatus.of(account == null ? null : account.status),
                staff.createdAt,
                staff.updatedAt);
    }
}
