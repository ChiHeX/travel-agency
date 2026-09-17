package com.travelagency.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateOrderRequest(
        @NotNull(message = "团期不能为空") Long departureId,
        @NotNull(message = "成人数量不能为空") @Min(value = 0, message = "成人数量不能为负数") Integer adultCount,
        @NotNull(message = "儿童数量不能为空") @Min(value = 0, message = "儿童数量不能为负数") Integer childCount,
        @NotBlank(message = "联系人姓名不能为空") String contactName,
        @NotBlank(message = "联系人手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "联系人手机号格式不正确") String contactPhone,
        @Email(message = "联系人邮箱格式不正确") String contactEmail,
        @NotEmpty(message = "至少需要一位出行人") @Valid List<TravelerSnapshotRequest> travelers,
        String remark) {

    public record TravelerSnapshotRequest(
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
}
