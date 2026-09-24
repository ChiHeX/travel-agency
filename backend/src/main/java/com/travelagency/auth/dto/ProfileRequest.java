package com.travelagency.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 资料修改请求，对齐契约 ProfileUpdateRequest：required [nickname]，头像字段名为 avatarUrl。
 */
public record ProfileRequest(
        @NotBlank(message = "昵称不能为空") @Size(max = 32, message = "昵称不能超过 32 个字符") String nickname,
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
        @Email(message = "邮箱格式不正确") String email,
        @Size(max = 64, message = "真实姓名不能超过 64 个字符") String realName,
        @Size(max = 500, message = "头像地址不能超过 500 个字符") String avatarUrl) {
}
