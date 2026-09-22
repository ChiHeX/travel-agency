package com.travelagency.auth.dto;

import com.travelagency.common.validation.PasswordRules;
import com.travelagency.common.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求，对齐契约 PasswordChangeRequest：
 * required [currentPassword, newPassword]，两者 minLength 8 / maxLength 72，
 * additionalProperties=false —— 因此这里只允许这两个字段，多传字段会被全局
 * FAIL_ON_UNKNOWN_PROPERTIES 直接判成 400。
 *
 * <p><b>为什么 {@code @Size} 之外还要 {@link Utf8ByteLength}</b>：契约的 72 是
 * <i>字符</i>数，BCrypt 的 72 是 <i>UTF-8 字节</i>数。25 个汉字只有 25 个字符却占 75 字节，
 * 能通过 {@code @Size(max = 72)}，随后 {@code BCryptPasswordEncoder#encode} 抛
 * {@code IllegalArgumentException: password cannot be more than 72 bytes}，被兜底成 500。
 * 两个约束叠加后，超限输入在进入 service 之前就以 422 拒绝，密码不会被改动。</p>
 */
public record PasswordChangeRequest(
        @NotBlank(message = "原密码不能为空")
        @Size(min = PasswordRules.MIN_CHARS, max = PasswordRules.MAX_CHARS, message = "原密码长度应为 8-72 位")
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String currentPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = PasswordRules.MIN_CHARS, max = PasswordRules.MAX_CHARS, message = "新密码长度应为 8-72 位")
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String newPassword) {
}
