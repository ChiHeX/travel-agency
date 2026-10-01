package com.travelagency.domain.dto;

import com.travelagency.common.validation.PasswordRules;
import com.travelagency.common.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 创建工作人员账号请求，对齐契约 StaffCreateRequest：
 * required [username, password, realName, employeeNo]，additionalProperties=false。
 *
 * <p>此前缺 {@code employeeNo}，服务端改用 {@code "EMP" + user.id} 自动生成。
 * 结果是：任何按契约发请求的调用方都会因为多出 {@code employeeNo} 字段被全局
 * FAIL_ON_UNKNOWN_PROPERTIES 判成 400 —— 契约里冻结的请求体根本发不进来。
 * <p>现在工号由调用方给定，与创建导游（账号 + 业务档案一次写入）的形状保持一致。</p>
 *
 * <p>{@code password} 的字节上限见 {@link Utf8ByteLength}：契约的 72 是字符数，
 * BCrypt 的 72 是 UTF-8 字节数，25 个汉字能过前者却撑爆后者。</p>
 *
 * <p><b>长度上限按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径，数的是字符（码点），而 {@code @Size} 数的是 UTF-16 码元 ——
 * 一个 emoji 会被算成 2，于是出现「契约允许、实现却回 422」（64 个 emoji 的姓名，
 * 码点 64 ≤ 64 合法，码元却是 128），而 {@code VARCHAR(64)} 在 utf8mb4 下存得下 64 个码点。
 * {@code username} 不在此列：契约用 ASCII 正则 {@code ^[A-Za-z0-9_]{3,32}$} 限定它，
 * 码点与码元在合法输入上恒等，继续用 {@code @Pattern}。</p>
 */
public record StaffAccountRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "用户名应为 3-32 位字母、数字或下划线") String username,
        @NotBlank(message = "初始密码不能为空")
        @CodePointLength(min = PasswordRules.MIN_CHARS, max = PasswordRules.MAX_CHARS, message = "密码长度应为 8-72 位")
        @Utf8ByteLength(max = PasswordRules.MAX_UTF8_BYTES, message = PasswordRules.BYTE_LIMIT_MESSAGE)
        String password,
        @NotBlank(message = "姓名不能为空")
        @CodePointLength(min = 1, max = 64, message = "姓名长度应为 1-64 位") String realName,
        @CodePointLength(max = 20, message = "手机号长度不能超过 20 位") String phone,
        @NotBlank(message = "员工工号不能为空")
        @CodePointLength(min = 1, max = 32, message = "员工工号长度应为 1-32 位") String employeeNo,
        @CodePointLength(max = 64, message = "部门长度不能超过 64 位") String department,
        @CodePointLength(max = 64, message = "岗位长度不能超过 64 位") String position) {
}
