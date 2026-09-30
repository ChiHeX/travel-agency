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
 * <p>其余文本字段的长度按 Unicode 码点计数（{@link CodePointLength}），与契约
 * {@code maxLength} 同口径；{@code @Size} 数的是 UTF-16 码元，会把契约允许的 emoji 姓名误拒。</p>
 */
public record StaffAccountRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "用户名应为 3-32 位字母、数字或下划线") String username,
        @NotBlank(message = "初始密码不能为空")
        @CodePointLength(min = PasswordRules.MIN_CHARS, max = PasswordRules.MAX_CHARS,
                message = "密码长度应为 8-72 位")
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
