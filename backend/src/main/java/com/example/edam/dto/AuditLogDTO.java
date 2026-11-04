package com.example.edam.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志 DTO（前端要求 snake_case）
 *
 * 由 AuditService.list() 拼装：OperationLog + sys_user.employee_no + result 文本化
 */
@Data
public class AuditLogDTO {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("employee_no")
    private String employeeNo;

    @JsonProperty("operation_type")
    private String operationType;

    @JsonProperty("resource_type")
    private String resourceType;

    @JsonProperty("resource_id")
    private Long resourceId;

    @JsonProperty("ip_address")
    private String ip;

    @JsonProperty("user_agent")
    private String userAgent;

    /** HTTP 方法（GET/POST/PUT/PATCH/DELETE） */
    @JsonProperty("http_method")
    private String httpMethod;

    /** 命中的路由模板，如 {@code /videos/{video_id}} */
    @JsonProperty("endpoint")
    private String endpoint;

    /** HTTP 响应状态码 */
    @JsonProperty("status_code")
    private Integer statusCode;

    /** 服务端处理耗时（毫秒） */
    @JsonProperty("duration_ms")
    private Integer durationMs;

    /** 会话 ID */
    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("detail")
    private String detail;

    @JsonProperty("timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime timestamp;

    /** "success" / "denied" / "failure"，与前端 AuditLog.result 类型对应 */
    @JsonProperty("result")
    private String result;

    /** OperationLog.result (int) → 字符串 */
    public static String textResult(Integer code) {
        if (code == null) return "failure";
        return switch (code) {
            case 1 -> "success";
            case 2 -> "failure";
            case 3 -> "denied";
            default -> "failure";
        };
    }
}