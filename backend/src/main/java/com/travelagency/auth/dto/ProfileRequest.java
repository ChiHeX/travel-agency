package com.travelagency.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 资料修改请求，对齐契约 ProfileUpdateRequest：required [nickname]，头像字段名为 avatarUrl。
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2（32 个 emoji 的昵称，码点 32 ≤ 32 合法，码元却是 64），
 * 库内列宽同样是码点口径（{@code sys_user.nickname} 是 {@code VARCHAR(32)}），两边一致。</p>
 */
public record ProfileRequest(
        @NotBlank(message = "昵称不能为空") @CodePointLength(max = 32, message = "昵称不能超过 32 个字符") String nickname,
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
        @Email(message = "邮箱格式不正确") String email,
        @CodePointLength(max = 64, message = "真实姓名不能超过 64 个字符") String realName,
        @CodePointLength(max = 500, message = "头像地址不能超过 500 个字符") String avatarUrl) {
}
