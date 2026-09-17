package com.travelagency.common.exception;

/**
 * 业务异常。除 HTTP 状态码外，携带稳定的机器可读结果码（对齐契约错误码）。
 * 兼容旧的仅传 message 或 status+message 的用法，此时结果码由状态码推导。
 */
public class BusinessException extends RuntimeException {

    private final int status;
    private final String code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int status, String message) {
        this(status, defaultCode(status), message);
    }

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    private static String defaultCode(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "RESOURCE_STATE_CONFLICT";
            case 422 -> "BUSINESS_RULE_VIOLATION";
            case 429 -> "RATE_LIMITED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "INTERNAL_ERROR";
        };
    }
}
