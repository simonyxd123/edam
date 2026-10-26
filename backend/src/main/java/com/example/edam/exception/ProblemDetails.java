package com.example.edam.exception;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RFC 7807 Problem Details 响应体构造器。
 *
 * <p><b>为什么单独抽一个类</b>：错误响应体有三处产出，格式必须完全一致 ——
 * <ol>
 *   <li>{@link GlobalExceptionHandler} —— 控制器/服务层抛出的异常</li>
 *   <li>{@link com.example.edam.security.ProblemDetailsEntryPoint} ——
 *       未认证（401），由 Spring Security 过滤器链产出</li>
 *   <li>{@link com.example.edam.security.ProblemDetailsAccessDeniedHandler} ——
 *       已认证但权限不足（403），同样由过滤器链产出</li>
 * </ol>
 *
 * <p>第 2、3 条在过滤���链里，<b>不经过 DispatcherServlet</b>，
 * 所以 {@code @RestControllerAdvice} 根本管不到它们 —— 之前这两条路径
 * 返回的是 Spring Security 默认的<b>空 body 403</b>，与 OpenAPI 里
 * {@code Error} schema（type/title/status/trace_id 必填）对不上，
 * 前端也拿不到 {@code detail} 去显示"为什么没权限"。
 *
 * <p>字段与 {@code doc/openapi.yaml} 的 {@code Error} schema 一致，
 * 也与开发用 mock（{@code dev/mock/server.py}）的 {@code _send_error} 一致
 * —— 三处同构，e2e 才能对着同一份契约断言。
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    /**
     * 构造 Problem Details 响应体。
     *
     * @param status HTTP 状态码
     * @param title  简短标题（如 Unauthorized / Forbidden）
     * @param detail 面向用户的说明
     * @param code   业务错误码（如 AUTH_401）
     */
    public static Map<String, Object> of(HttpStatus status, String title, String detail, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        body.put("trace_id", traceId());
        body.put("timestamp", OffsetDateTime.now().toString());
        return body;
    }

    /**
     * 取链路追踪 ID。
     *
     * <p>优先用 MDC 里已有的（由上游网关/过滤器写入），
     * 没有就现场生成一个 —— 至少让用户报障时能给出一个可grep的串。
     */
    public static String traceId() {
        String traceId = MDC.get("trace_id");
        return traceId == null ? UUID.randomUUID().toString() : traceId;
    }
}
