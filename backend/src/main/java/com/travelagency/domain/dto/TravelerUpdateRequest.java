package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

import java.time.LocalDate;

/**
 * 常用出行人更新请求，对齐契约 TravelerUpdateRequest。
 *
 * <p>与新增请求 {@link TravelerRequest} 的唯一差别是 {@code idNo} 允许省略：契约写明
 * 「仅在需要更换证件号码时提交；省略表示保留原值」。</p>
 *
 * <p>这个差别是必需的，不是可选优化：列表接口按最小必要原则只返回脱敏后的
 * {@code idNoMasked}，前端编辑弹窗因此拿不到原始证件号码、提交时会省略 {@code idNo}。
 * 此前更新接口复用了 {@link TravelerRequest}（其中 {@code idNo} 带 @NotBlank），
 * 导致「编辑出行人但不改证件号」这一正常流程被 422 拒绝。</p>
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是出现「契约允许、实现却回 422」。库内列宽同样是码点口径
 * （{@code VARCHAR(64)} 在 utf8mb4 下就是 64 个字符），两边一致。</p>
 */
public record TravelerUpdateRequest(
        @NotBlank(message = "出行人姓名不能为空") @CodePointLength(max = 64, message = "出行人姓名不能超过 64 字") String name,
        @NotBlank(message = "性别不能为空")
        @Pattern(regexp = "MALE|FEMALE|OTHER", message = "性别取值不合法") String gender,
        @NotNull(message = "出生日期不能为空") LocalDate birthDate,
        @NotBlank(message = "证件类型不能为空")
        @Pattern(regexp = "CHINESE_ID_CARD|PASSPORT|OTHER", message = "证件类型取值不合法") String idType,
        @CodePointLength(min = 3, max = 64, message = "证件号码长度应为 3-64 位") String idNo,
        @CodePointLength(max = 20, message = "手机号不能超过 20 位") String phone,
        @NotBlank(message = "紧急联系人姓名不能为空") @CodePointLength(max = 64, message = "紧急联系人姓名不能超过 64 字") String emergencyName,
        @NotBlank(message = "紧急联系人电话不能为空")
        @CodePointLength(min = 3, max = 20, message = "紧急联系人电话长度应为 3-20 位") String emergencyPhone) {
}
