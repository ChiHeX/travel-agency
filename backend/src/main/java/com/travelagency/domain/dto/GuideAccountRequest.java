package com.travelagency.domain.dto;

import com.travelagency.common.validation.PasswordRules;
import com.travelagency.common.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/**
 * 创建导游账号（账号 + 业务档案一次写入）请求。
 *
 * <p>{@code password} 同时受契约的 72 字符与 BCrypt 的 72 UTF-8 字节两个上限约束，
 * 两者单位不同，缺一不可 —— 详见 {@link PasswordRules}。</p>
 */
public record GuideAccountRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$") String username,
        @NotBlank
        @Size(min = PasswordRules.MIN_CHARS, max = PasswordRules.MAX_CHARS)
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String password,
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(min = 3, max = 20) String phone,
        @Size(max = 1000) String intro) {
}
