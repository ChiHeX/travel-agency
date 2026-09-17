package com.travelagency.common.api;

/**
 * 单条详细错误，对应契约 ErrorDetail。
 * field 为出错字段名，非字段级错误可为 null。
 */
public record ApiError(String field, String code, String message) {

    public ApiError(String field, String message) {
        this(field, "INVALID", message);
    }
}
