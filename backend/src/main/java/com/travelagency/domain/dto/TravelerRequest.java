package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

import java.time.LocalDate;

/**
 * 常用出行人新增请求，逐字段对齐契约 TravelerRequest。
 *
 * <p>required = [name, gender, birthDate, idType, idNo, emergencyName, emergencyPhone]；
 * {@code phone} 是唯一可空字段（type: [string, 'null']，maxLength 20，契约未规定格式）。</p>
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是出现「契约允许、实现却回 422」（64 个 emoji 的姓名，
 * 码点 64 ≤ 64 合法，码元却是 128）。库内列宽同样是码点口径
 * （{@code VARCHAR(64)} 在 utf8mb4 下就是 64 个字符），两边一致。</p>
 */
public record TravelerRequest(
        @NotBlank(message = "出行人姓名不能为空") @CodePointLength(max = 64, message = "出行人姓名不能超过 64 字") String name,
        @NotBlank(message = "性别不能为空")
        @Pattern(regexp = "MALE|FEMALE|OTHER", message = "性别取值不合法") String gender,
        @NotNull(message = "出生日期不能为空") LocalDate birthDate,
        @NotBlank(message = "证件类型不能为空")
        @Pattern(regexp = "CHINESE_ID_CARD|PASSPORT|OTHER", message = "证件类型取值不合法") String idType,
        @NotBlank(message = "证件号码不能为空")
        @CodePointLength(min = 3, max = 64, message = "证件号码长度应为 3-64 位") String idNo,
        @CodePointLength(max = 20, message = "手机号不能超过 20 位") String phone,
        @NotBlank(message = "紧急联系人姓名不能为空") @CodePointLength(max = 64, message = "紧急联系人姓名不能超过 64 字") String emergencyName,
        @NotBlank(message = "紧急联系人电话不能为空")
        @CodePointLength(min = 3, max = 20, message = "紧急联系人电话长度应为 3-20 位") String emergencyPhone) {
}
