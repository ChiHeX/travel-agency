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
            /**
             * 来源常用出行人 ID（可空）。契约 {@code OrderTravelerRequest} 明确列有该字段
             * （且 additionalProperties 为 false），前端 OrderCreateView 每次提交都会带上
             * {@code sourceTravelerId}（即使为 null 也会被序列化）。
             *
             * <p>此前请求模型缺少该字段：在 Jackson 允许未知字段时被静默丢弃，仅表现为
             * 「快照丢掉了来源关联」；而一旦开启 FAIL_ON_UNKNOWN_PROPERTIES，它就从静默丢弃
             * 变成硬报错 —— 前端下单会直接返回 400，整个下单流程不可用。</p>
             */
            Long sourceTravelerId,
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
            @Size(min = 3, max = 20, message = "紧急联系人电话长度应为 3-20 位") String emergencyPhone,
            /**
             * 出行人类型，取值 ADULT / CHILD。
             *
             * <p>契约 {@code OrderTravelerRequest} 的 required 中列有该字段，前端
             * OrderCreateView 也一直在提交它；此前请求模型缺少该字段，导致 Jackson
             * 静默丢弃调用方的真实意图，服务端只能按「前 adultCount 位为成人」猜，
             * “第 1 位是儿童、第 2 位是成人”这类顺序会被写错快照。</p>
             */
            @NotBlank(message = "出行人类型不能为空")
            @Pattern(regexp = "ADULT|CHILD", message = "出行人类型取值不合法") String travelerType) {
    }
}
