package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 账号启停请求，对齐契约 AccountStatusUpdateRequest（required [status]，枚举 AccountStatus）。
 *
 * <p>不要与通用的 {@link StatusRequest} 混用：那个是自由文本，服务端再各自校验取值，
 * 曾经因为拿它去接账号状态而直接 {@code Integer.parseInt("ACTIVE")} 抛 NumberFormatException。
 * 这里用枚举约束把非法值挡在 422 上，落到写入代码时状态只可能是 ACTIVE 或 DISABLED。</p>
 */
public record AccountStatusUpdateRequest(
        @NotBlank(message = "账号状态不能为空")
        @Pattern(regexp = "ACTIVE|DISABLED", message = "账号状态仅支持 ACTIVE 或 DISABLED") String status) {
}
