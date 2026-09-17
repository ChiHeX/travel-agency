package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record TravelerRequest(
        @NotBlank(message = "出行人姓名不能为空") @Size(max = 64, message = "出行人姓名不能超过 64 字") String name,
        @NotBlank(message = "性别不能为空")
        @Pattern(regexp = "MALE|FEMALE|OTHER", message = "性别取值不合法") String gender,
        LocalDate birthDate,
        @NotBlank(message = "证件类型不能为空")
        @Pattern(regexp = "CHINESE_ID_CARD|PASSPORT|OTHER", message = "证件类型取值不合法") String idType,
        @NotBlank(message = "证件号码不能为空")
        @Size(min = 3, max = 64, message = "证件号码长度应为 3-64 位") String idNo,
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
        @NotBlank(message = "紧急联系人姓名不能为空") @Size(max = 64, message = "紧急联系人姓名不能超过 64 字") String emergencyName,
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "紧急联系人电话格式不正确") String emergencyPhone) {
}
