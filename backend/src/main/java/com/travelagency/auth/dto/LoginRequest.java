package com.travelagency.auth.dto;

import com.travelagency.common.validation.PasswordRules;
import com.travelagency.common.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求，对齐契约 LoginRequest（required [username, password]，additionalProperties=false）。
 *
 * <p>这里<b>只</b>加 BCrypt 的字节上限，不加 {@code @Size(min = 8)}：契约虽然给
 * LoginRequest.password 也写了 minLength 8，但「密码太短」在登录语义下就该是
 * 401「用户名或密码错误」；加长度下限会把 401 变成 422，等于告诉调用方
 * 「该用户名存在、只是密码长度不对」。字节上限则不同：超过 72 UTF-8 字节的字符串
 * <b>不可能</b>是任何账号的合法密码（所有写入侧一律拒绝，见 {@link PasswordRules}），
 * 判 422 与契约的 maxLength 一致，也不泄露任何信息。</p>
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空")
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String password) {
}
