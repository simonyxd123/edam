package com.example.edam.exception;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 全局异常处理器
 * 统一返回 RFC 7807 Problem Details 格式
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Resource Not Found", ex.getMessage(), "RES_404");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), "AUTH_403");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getAllErrors().stream()
                .map(e -> e.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, "Validation Failed", detail, "VAL_400");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), "REQ_400");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException ex) {
        Map<String, Object> body = Map.of(
            "type", URI.create("about:blank"),
            "title", "Too Many Requests",
            "status", HttpStatus.TOO_MANY_REQUESTS.value(),
            "detail", ex.getMessage(),
            "code", "RL_429",
            "dimension", ex.getDimension(),
            "retry_after_seconds", ex.getRetryAfterSeconds(),
            "remaining_tokens", ex.getRemainingTokens(),
            "trace_id", getOrCreateTraceId(),
            "timestamp", OffsetDateTime.now().toString()
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
            .header("X-RateLimit-Dimension", ex.getDimension())
            .body(body);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountLocked(AccountLockedException ex) {
        Map<String, Object> body = Map.of(
            "type", URI.create("about:blank"),
            "title", "Account Locked",
            "status", HttpStatus.LOCKED.value(),
            "detail", "连续登录失败过多，账号已被锁定 30 分钟",
            "code", "AUTH_ACCOUNT_LOCKED",
            "retry_after_seconds", ex.getRemainingLockSeconds(),
            "trace_id", getOrCreateTraceId(),
            "timestamp", OffsetDateTime.now().toString()
        );
        return ResponseEntity.status(HttpStatus.LOCKED).body(body);
    }

    /**
     * 上传文件超过 spring.servlet.multipart.max-file-size（当前 2GB）。
     * 属于客户端发得太大的正常拒绝，不该记 ERROR。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("upload_exceeds_limit: {}", ex.getMessage());
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large",
                    "文件超过服务端上限（2GB），请压缩后再上传", "UPLOAD_413");
    }

    /**
     * multipart 解析失败。
     *
     * 最常见的一种是「客户端主动断开」——关标签页、离开页面、上传超时被
     * XHR abort，都会让 Tomcat 读到 EOF。此时服务端并无故障，是正常现象，
     * 记 WARN 且不打整页堆栈，免得把真实错误淹没在噪音里。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex) {
        Throwable cause = ex.getMostSpecificCause();
        boolean aborted = isClientAbort(ex);
        if (aborted) {
            log.warn("client_aborted_upload, cause={}", cause != null ? cause.toString() : "unknown");
        } else {
            log.warn("multipart_parse_failed, cause={}", cause != null ? cause.toString() : "unknown");
        }
        return build(HttpStatus.BAD_REQUEST, "Bad Request",
                    aborted ? "上传已中断，请重试" : "上传内容解析失败，请重试", "UPLOAD_400");
    }

    /**
     * 沿 cause 链判断是否为客户端中断。
     *
     * ClientAbortException 是 Tomcat 内部类，但 tomcat-embed-core 在编译期就在
     * classpath 上，可直接引用；EOFException 作为兜底（不依赖容器实现）。
     */
    private boolean isClientAbort(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ClientAbortException || c instanceof EOFException) {
                return true;
            }
            if (c instanceof IOException && c.getMessage() != null
                    && c.getMessage().toLowerCase().contains("broken pipe")) {
                return true;
            }
            if (c.getCause() == c) break; // 防自引用死循环
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                    "服务内部错误，请稍后重试", "INT_500");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String title, String detail, String code) {
        Map<String, Object> body = Map.of(
            "type", URI.create("about:blank"),
            "title", title,
            "status", status.value(),
            "detail", detail,
            "code", code,
            "trace_id", getOrCreateTraceId(),
            "timestamp", OffsetDateTime.now().toString()
        );
        return ResponseEntity.status(status).body(body);
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get("trace_id");
        if (traceId == null) traceId = UUID.randomUUID().toString();
        return traceId;
    }
}