package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 修改工作人员资料请求，对齐契约 StaffUpdateRequest：
 * required [realName, employeeNo]，additionalProperties=false。
 *
 * <p>刻意不含 {@code username} / {@code userId} / {@code status}：账号绑定关系与启停
 * 各有专属端点，从资料修改接口注入这些字段应当被全局 FAIL_ON_UNKNOWN_PROPERTIES 判成 400。</p>
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是出现「契约允许、实现却回 422」。
 * 库内列宽同样是码点口径（{@code VARCHAR(64)} 在 utf8mb4 下就是 64 个字符），两边一致。</p>
 */
public record StaffUpdateRequest(
        @NotBlank(message = "姓名不能为空")
        @CodePointLength(min = 1, max = 64, message = "姓名长度应为 1-64 位") String realName,
        @CodePointLength(max = 20, message = "手机号长度不能超过 20 位") String phone,
        @NotBlank(message = "员工工号不能为空")
        @CodePointLength(min = 1, max = 32, message = "员工工号长度应为 1-32 位") String employeeNo,
        @CodePointLength(max = 64, message = "部门长度不能超过 64 位") String department,
        @CodePointLength(max = 64, message = "岗位长度不能超过 64 位") String position) {
}
