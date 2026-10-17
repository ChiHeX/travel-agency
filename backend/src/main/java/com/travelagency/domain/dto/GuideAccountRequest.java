package com.travelagency.domain.dto;

import com.travelagency.common.validation.PasswordRules;
import com.travelagency.common.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 创建导游账号（账号 + 业务档案一次写入）请求。
 *
 * <p><b>长度一律按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的
 * {@code minLength/maxLength} 是 JSON Schema 口径，数的是字符（码点），而后端原先用的
 * {@code @Size} 数的是 UTF-16 码元 —— 两者只在 BMP 字符下等价。一个 emoji 是 1 个码点却是
 * 2 个码元，64 个 emoji 的姓名（契约允许，{@code guide.name VARCHAR(64)} 也存得下）
 * 会被 {@code @Size(max = 64)} 以 422 误拒。酒店资料（{@code HotelCreateRequest}）已经统一
 * 到这个口径，导游这一档此前漏掉了。</p>
 *
 * <p>{@code password} 另外单独受 BCrypt 的 72 <b>UTF-8 字节</b>上限约束，两者单位不同，缺一不可
 * —— 详见 {@link PasswordRules}。</p>
 */
public record GuideAccountRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$") String username,

        @NotBlank
        @CodePointLength(min = PasswordRules.MIN_CHARS, max = PasswordRules.MAX_CHARS)
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String password,

        @NotBlank @CodePointLength(max = 64, message = "导游姓名最多 64 个字符") String name,

        @NotBlank @CodePointLength(min = 3, max = 20, message = "联系电话长度应为 3-20 个字符") String phone,

        @CodePointLength(max = 1000, message = "个人简介最多 1000 个字符") String intro) {
}
