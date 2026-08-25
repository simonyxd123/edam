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