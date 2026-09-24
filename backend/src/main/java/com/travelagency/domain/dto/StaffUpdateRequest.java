package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改工作人员资料请求，对齐契约 StaffUpdateRequest：
 * required [realName, employeeNo]，additionalProperties=false。
 *
 * <p>刻意不含 {@code username} / {@code userId} / {@code status}：账号绑定关系与启停
 * 各有专属端点，从资料修改接口注入这些字段应当被全局 FAIL_ON_UNKNOWN_PROPERTIES 判成 400。</p>
 */
public record StaffUpdateRequest(
        @NotBlank(message = "姓名不能为空")
        @Size(min = 1, max = 64, message = "姓名长度应为 1-64 位") String realName,
        @Size(max = 20, message = "手机号长度不能超过 20 位") String phone,
        @NotBlank(message = "员工工号不能为空")
        @Size(min = 1, max = 32, message = "员工工号长度应为 1-32 位") String employeeNo,
        @Size(max = 64, message = "部门长度不能超过 64 位") String department,
        @Size(max = 64, message = "岗位长度不能超过 64 位") String position) {
}
