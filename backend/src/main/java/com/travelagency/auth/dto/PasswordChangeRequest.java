package com.travelagency.auth.dto;

import com.travelagency.common.validation.PasswordRules;
import com.travelagency.common.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 修改密码请求，对齐契约 PasswordChangeRequest：
 * required [currentPassword, newPassword]，additionalProperties=false ——
 * 因此这里只允许这两个字段，多传字段会被全局 FAIL_ON_UNKNOWN_PROPERTIES 直接判成 400。
 *
 * <p><b>两个字段的口径不同，刻意不共用同一套长度约束：</b></p>
 * <ul>
 *   <li>{@code newPassword} 是「设置新口令」，套用完整的设置规则：8–72 个字符
 *       （{@link CodePointLength}，与契约 maxLength 同口径）+ UTF-8 不超过 72 字节；</li>
 *   <li>{@code currentPassword} 是「验证已有口令」，只要求非空且 UTF-8 不超过 72 字节，
 *       <b>不套用新的设置规则</b>（既没有 8 字符下限，也没有码点上限 —— 上限由字节上限蕴含）。</li>
 * </ul>
 *
 * <p>为什么原密码不能套用设置规则：历史口令是按 <b>UTF-16 码元</b> 口径创建的
 * （{@code @Size(min = 8)}），例如「😀😀😀😀」只有 4 个字符（码点）却有 8 个码元，
 * 当时能注册、能登录、也确实存在库里。改密接口若在校验原密码之前就以「不足 8 个字符」回 422，
 * 这些用户就再也改不了密码 —— 而契约又不提供管理员重置入口（见 API.md §4.2），
 * 等于把旧账号永久锁死在旧口令上。原密码的正确校验方式是<b>哈希匹配</b>：
 * 长度不合法时 {@code passwordEncoder.matches} 自然不匹配，回 422「原密码不正确」。
 * 字节上限必须保留：超过 72 字节的输入不可能是任何已存口令，且会让 BCrypt 抛异常变成 500。</p>
 */
public record PasswordChangeRequest(
        @NotBlank(message = "原密码不能为空")
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String currentPassword,
        @NotBlank(message = "新密码不能为空")
        @CodePointLength(min = PasswordRules.MIN_CHARS, max = PasswordRules.MAX_CHARS, message = "新密码长度应为 8-72 位")
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String newPassword) {
}
