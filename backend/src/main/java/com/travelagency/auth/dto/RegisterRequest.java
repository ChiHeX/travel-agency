package com.travelagency.auth.dto;

import com.travelagency.common.validation.PasswordRules;
import com.travelagency.common.validation.Utf8ByteLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 4, max = 32, message = "用户名长度应为 4-32 位")
        String username,
        // 上限此前写的是 64，比契约 RegisterRequest.password 的 maxLength: 72 更严，
        // 属于「实现与契约矛盾」：按契约发 65 位的密码会被拒。这里改回 72 与契约一致，
        // 同时补 BCrypt 的字节上限（两者单位不同，见 PasswordRules）。
        @NotBlank(message = "密码不能为空")
        @Size(min = PasswordRules.MIN_CHARS, max = PasswordRules.MAX_CHARS, message = "密码长度应为 8-72 位")
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String password,
        @NotBlank(message = "昵称不能为空")
        @Size(max = 32, message = "昵称不能超过 32 个字符")
        String nickname,
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,
        @Email(message = "邮箱格式不正确")
        String email) {
}
