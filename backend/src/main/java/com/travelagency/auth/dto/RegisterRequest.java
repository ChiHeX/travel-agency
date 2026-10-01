package com.travelagency.auth.dto;

import com.travelagency.common.validation.PasswordRules;
import com.travelagency.common.validation.Utf8ByteLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 注册请求，对齐契约 RegisterRequest（required [username, password, nickname]，
 * additionalProperties=false）。
 *
 * <p><b>{@code password} / {@code nickname} 的长度上限按 Unicode 码点计数</b>
 * （{@link CodePointLength}）：契约的 {@code maxLength} 是 JSON Schema 口径，数的是字符
 * （码点），而 {@code @Size} 数的是 UTF-16 码元 —— 一个 emoji 会被算成 2，于是出现
 * 「契约允许、实现却回 422」。{@code password} 的 BCrypt 字节上限另由
 * {@link Utf8ByteLength} 守住，两个上限单位不同。</p>
 *
 * <p>{@code username} 继续用 {@code @Size}：契约对它的约束是 ASCII 正则
 * {@code ^[A-Za-z0-9_]{3,32}$}（未声明 {@code maxLength}），落在合法输入上时码点与码元恒等，
 * 改口径没有意义。本类同时登记了该字段的两处已知契约差距（实现未校验该正则、
 * 下限是 4 而契约是 3），属于需要单独评审的行为变更，不在本次长度口径统一内处理。</p>
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 4, max = 32, message = "用户名长度应为 4-32 位")
        String username,
        // 上限此前写的是 64，比契约 RegisterRequest.password 的 maxLength: 72 更严，
        // 属于「实现与契约矛盾」：按契约发 65 位的密码会被拒。这里改回 72 与契约一致，
        // 同时补 BCrypt 的字节上限（两者单位不同，见 PasswordRules）。
        @NotBlank(message = "密码不能为空")
        @CodePointLength(min = PasswordRules.MIN_CHARS, max = PasswordRules.MAX_CHARS, message = "密码长度应为 8-72 位")
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String password,
        @NotBlank(message = "昵称不能为空")
        @CodePointLength(max = 32, message = "昵称不能超过 32 个字符")
        String nickname,
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,
        @Email(message = "邮箱格式不正确")
        String email) {
}
