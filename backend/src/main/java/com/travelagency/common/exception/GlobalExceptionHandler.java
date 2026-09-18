package com.travelagency.common.exception;

import com.travelagency.common.api.ApiError;
import com.travelagency.common.api.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 全局异常处理，统一转换为契约错误响应 { code, message, data:null, errors, traceId }，
 * 不向前端返回堆栈、SQL 或敏感信息。
 *
 * 必须声明 HIGHEST_PRECEDENCE：Spring Boot 的 ProblemDetailsExceptionHandler 自身带有 @Order(0)，
 * 未指定顺序的 @RestControllerAdvice 默认是 LOWEST_PRECEDENCE，会导致校验失败被它抢先处理成
 * RFC 7807（ProblemDetail）响应，从而破坏 docs/API.md 的统一信封约定。
 * 因为本类最终会兜底 Exception，所以必须为常见的 Spring MVC 异常逐个给出契约映射，
 * 否则它们会被降级成 500。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    /**
     * Bean Validation 校验失败：请求格式正确但字段语义不合法 → 422。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toApiError)
                .toList();
        String message = errors.isEmpty() ? "请求参数校验失败" : errors.get(0).message();
        return ResponseEntity.unprocessableContent()
                .body(ApiResponse.error("VALIDATION_ERROR", message, errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<ApiError> errors = ex.getConstraintViolations().stream()
                .map(this::toApiError)
                .toList();
        return ResponseEntity.unprocessableContent()
                .body(ApiResponse.error("VALIDATION_ERROR", "请求参数校验失败", errors));
    }

    /**
     * 方法级参数校验失败（Controller 上的 {@code @Validated} + 参数约束，或 Spring 内建的方法校验）。
     *
     * <p>必须显式列出：本类兜底了 {@code Exception}，而这个异常继承自
     * {@code ResponseStatusException}，既不是 {@code ErrorResponseException} 也不是
     * {@code ConstraintViolationException}，漏掉就会把参数校验失败降级成 500。</p>
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException ex) {
        List<ApiError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(error -> new ApiError("", "INVALID", error.getDefaultMessage()))
                .toList();
        String message = errors.isEmpty() || errors.get(0).message() == null
                ? "请求参数校验失败" : errors.get(0).message();
        return ResponseEntity.unprocessableContent()
                .body(ApiResponse.error("VALIDATION_ERROR", message, errors));
    }

    /**
     * JSON 解析失败、参数类型错误、缺少必填参数或必填请求头：请求格式错误 → 400。
     *
     * <p>MissingRequestHeaderException 必须显式列出：它继承自 ServletException 而非
     * ErrorResponseException，不会被下面的 Spring MVC 分支接住，漏掉就会掉进兜底变成 500。
     * 典型场景是契约要求的 Idempotency-Key 请求头缺失。</p>
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("BAD_REQUEST", "请求格式或参数类型不正确"));
    }

    /**
     * 已登录但角色/数据权限不足（@PreAuthorize 等抛出）→ 403。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("ACCESS_DENIED", "无权访问该资源"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("RESOURCE_NOT_FOUND", "请求的资源不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("METHOD_NOT_ALLOWED", "该资源不支持此请求方法"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error("UNSUPPORTED_MEDIA_TYPE", "不支持的请求内容类型"));
    }

    /**
     * 客户端 Accept 不接受任何可生成的响应类型。此时不能写入 JSON 信封（会被再次拒绝），
     * 因此返回无响应体的 406。
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    /**
     * Spring MVC 其余标准错误（含各类 ErrorResponseException）按其自身状态码透传，避免被兜底成 500。
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleSpringMvcError(ErrorResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == HttpStatus.NOT_ACCEPTABLE.value()) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
        }
        return ResponseEntity.status(status).body(ApiResponse.error(codeFor(status), messageFor(status)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled application exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
    }

    private String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 409 -> "RESOURCE_CONFLICT";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 422 -> "VALIDATION_ERROR";
            case 429 -> "TOO_MANY_REQUESTS";
            default -> status >= 500 ? "INTERNAL_ERROR" : "BAD_REQUEST";
        };
    }

    private String messageFor(int status) {
        return switch (status) {
            case 401 -> "请先登录";
            case 403 -> "无权访问该资源";
            case 404 -> "请求的资源不存在";
            case 405 -> "该资源不支持此请求方法";
            case 409 -> "请求与当前资源状态冲突";
            case 415 -> "不支持的请求内容类型";
            case 422 -> "请求参数校验失败";
            default -> status >= 500 ? "服务暂时不可用，请稍后重试" : "请求无法完成";
        };
    }

    private ApiError toApiError(FieldError error) {
        return new ApiError(error.getField(), "INVALID", error.getDefaultMessage());
    }

    private ApiError toApiError(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        return new ApiError(path, "INVALID", violation.getMessage());
    }
}
