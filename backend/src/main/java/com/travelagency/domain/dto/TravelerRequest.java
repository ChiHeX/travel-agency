package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 常用出行人新增请求，逐字段对齐契约 TravelerRequest。
 *
 * <p>required = [name, gender, birthDate, idType, idNo, emergencyName, emergencyPhone]；
 * {@code phone} 是唯一可空字段（type: [string, 'null']，maxLength 20，契约未规定格式）。</p>
 */
public record TravelerRequest(
        @NotBlank(message = "出行人姓名不能为空") @Size(max = 64, message = "出行人姓名不能超过 64 字") String name,
        @NotBlank(message = "性别不能为空")
        @Pattern(regexp = "MALE|FEMALE|OTHER", message = "性别取值不合法") String gender,
        @NotNull(message = "出生日期不能为空") LocalDate birthDate,
        @NotBlank(message = "证件类型不能为空")
        @Pattern(regexp = "CHINESE_ID_CARD|PASSPORT|OTHER", message = "证件类型取值不合法") String idType,
        @NotBlank(message = "证件号码不能为空")
        @Size(min = 3, max = 64, message = "证件号码长度应为 3-64 位") String idNo,
        @Size(max = 20, message = "手机号不能超过 20 位") String phone,
        @NotBlank(message = "紧急联系人姓名不能为空") @Size(max = 64, message = "紧急联系人姓名不能超过 64 字") String emergencyName,
        @NotBlank(message = "紧急联系人电话不能为空")
        @Size(min = 3, max = 20, message = "紧急联系人电话长度应为 3-20 位") String emergencyPhone) {
}
