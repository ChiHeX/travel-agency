package com.travelagency.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求，对齐契约 PasswordChangeRequest：
 * required [currentPassword, newPassword]，两者 minLength 8 / maxLength 72，
 * additionalProperties=false —— 因此这里只允许这两个字段，多传字段会被全局
 * FAIL_ON_UNKNOWN_PROPERTIES 直接判成 400。
 *
 * <p>长度上限取 72 而不是 64：契约就是 72，且 BCrypt 的 72 字节上限与之吻合。</p>
 */
public record PasswordChangeRequest(
        @NotBlank(message = "原密码不能为空")
        @Size(min = 8, max = 72, message = "原密码长度应为 8-72 位") String currentPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 72, message = "新密码长度应为 8-72 位") String newPassword) {
}
