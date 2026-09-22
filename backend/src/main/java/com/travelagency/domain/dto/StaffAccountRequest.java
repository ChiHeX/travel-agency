package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建工作人员账号请求，对齐契约 StaffCreateRequest：
 * required [username, password, realName, employeeNo]，additionalProperties=false。
 *
 * <p>此前缺 {@code employeeNo}，服务端改用 {@code "EMP" + user.id} 自动生成。
 * 结果是：任何按契约发请求的调用方都会因为多出 {@code employeeNo} 字段被全局
 * FAIL_ON_UNKNOWN_PROPERTIES 判成 400 —— 契约里冻结的请求体根本发不进来。
 * 现在工号由调用方给定，与创建导游（账号 + 业务档案一次写入）的形状保持一致。</p>
 */
public record StaffAccountRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "用户名应为 3-32 位字母、数字或下划线") String username,
        @NotBlank(message = "初始密码不能为空")
        @Size(min = 8, max = 72, message = "密码长度应为 8-72 位") String password,
        @NotBlank(message = "姓名不能为空")
        @Size(min = 1, max = 64, message = "姓名长度应为 1-64 位") String realName,
        @Size(max = 20, message = "手机号长度不能超过 20 位") String phone,
        @NotBlank(message = "员工工号不能为空")
        @Size(min = 1, max = 32, message = "员工工号长度应为 1-32 位") String employeeNo,
        @Size(max = 64, message = "部门长度不能超过 64 位") String department,
        @Size(max = 64, message = "岗位长度不能超过 64 位") String position) {
}
