package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 评价可见状态调整请求，对齐契约 ReviewStatusUpdateRequest（VISIBLE / HIDDEN）。
 */
public record ReviewStatusUpdateRequest(
        @NotBlank(message = "状态不能为空") String status) {
}
