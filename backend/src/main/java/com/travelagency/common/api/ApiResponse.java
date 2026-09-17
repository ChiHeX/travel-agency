package com.travelagency.common.api;

import java.util.List;
import java.util.UUID;

/**
 * 统一响应包装，对齐 docs/API.md 第 5 节契约：
 * 成功 { code:"OK", message:"success", data, errors:[], traceId }
 * 失败 { code, message, data:null, errors:[...], traceId }
 */
public record ApiResponse<T>(String code, String message, T data, List<ApiError> errors, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "success", data, List.of(), newTraceId());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return error(code, message, List.of());
    }

    public static <T> ApiResponse<T> error(String code, String message, List<ApiError> errors) {
        return new ApiResponse<>(code, message, null, errors == null ? List.of() : errors, newTraceId());
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
