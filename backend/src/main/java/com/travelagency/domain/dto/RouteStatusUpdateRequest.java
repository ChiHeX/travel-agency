package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 线路上下架请求，对齐契约 {@code RouteStatusUpdateRequest}。
 *
 * <p>只允许 PUBLISHED / OFFLINE：DRAFT 是创建线路时的初始状态，不能通过上下架接口回退；
 * 取值非法时按契约返回 422。</p>
 */
public record RouteStatusUpdateRequest(
        @NotBlank(message = "线路状态不能为空")
        @Pattern(regexp = "PUBLISHED|OFFLINE", message = "线路状态只能是 PUBLISHED 或 OFFLINE") String status) {
}
