package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import com.example.edam.dto.AuditLogDTO;
import com.example.edam.dto.PaginationResponses;
import com.example.edam.model.OperationLog;
import com.example.edam.repository.OperationLogRepository;
import com.example.edam.service.AuditService;
import com.example.edam.security.CurrentUserId;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志 Controller
 * 对应 openapi.yaml tag: audit
 */
@RestController
@RequestMapping("/audit/logs")
@RequiredArgsConstructor
public class AuditController {

    private final OperationLogRepository operationLogRepository;
    private final AuditService auditService;

    /**
     * 列表（分页 + 过滤），返回 AuditLogDTO（含 employee_no）
     *
     * 时间参数格式：yyyy-MM-dd HH:mm:ss（与 JacksonConfig 一致）
     *
     * <p><b>这个接口本身也要审计</b>：谁在什么时候翻看了审计日志，
     * 是合规审计里的关键一环 —— "管理员查了日志然后删了痕迹"这种场景，
     * 没有这条记录就查不出来。
     */
    @GetMapping
    @AuditOp(op = AuditOpType.LIST, resourceType = "audit_log",
        detail = "page=#page, size=#page_size")
    public Map<String, Object> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size,
        @RequestParam(required = false) Long user_id,
        @RequestParam(required = false) String operation_type,
        @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String resource_type
    ) {
        Page<AuditLogDTO> auditPage = auditService.list(
            page, page_size, user_id, operation_type, start_time, end_time,
            result, resource_type);

        // 统一分页壳（见 dto/PaginationResponses）
        return PaginationResponses.of(auditPage);
    }

    /**
     * 操作类型清单（前端筛选下拉用）。
     *
     * <p>前端不再自己硬编码一份 —— 两份清单必然漂移，
     * 2026-11-03 之前就是因为这个，前端下拉里的 {@code upload}/{@code view}
     * 和后端实际写的 {@code create}/{@code detail} 对不上，筛选框形同虚设。
     */
    @GetMapping("/operation-types")
    @AuditOp(op = AuditOpType.LIST, resourceType = "audit_log")
    public Map<String, Object> operationTypes() {
        Map<String, Object> response = new HashMap<>();
        response.put("operation_types", AuditOpType.ALL);
        return response;
    }

    /**
     * 资源类型清单（前端筛选下拉用）—— 从库里 DISTINCT，不写死。
     */
    @GetMapping("/resource-types")
    @AuditOp(op = AuditOpType.LIST, resourceType = "audit_log")
    public Map<String, Object> resourceTypes() {
        Map<String, Object> response = new HashMap<>();
        response.put("resource_types", auditService.distinctResourceTypes());
        return response;
    }

    @GetMapping("/{log_id}")
    @AuditOp(op = AuditOpType.DETAIL, resourceType = "audit_log", resourceId = "#logId")
    public OperationLog getById(@PathVariable("log_id") Long logId) {
        return operationLogRepository.selectById(logId);
    }

    @PostMapping("/export")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:audit_export')")
    @AuditOp(op = AuditOpType.EXPORT, resourceType = "audit_log",
        detail = "start=#request.startTime, end=#request.endTime, format=#request.format")
    public ResponseEntity<Map<String, Object>> export(
        @RequestBody ExportRequest request,
        @CurrentUserId Long operatorId
    ) {
        String taskId = UUID.randomUUID().toString();
        String downloadUrl = String.format(
            "https://api.example.com/api/v1/audit/exports/%s/download?token=%s",
            taskId, UUID.randomUUID().toString());

        Map<String, Object> response = new HashMap<>();
        response.put("task_id", taskId);
        response.put("download_url", downloadUrl);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Data
    public static class ExportRequest {
        private String startTime;
        private String endTime;
        private String format;
        private Map<String, Object> filter;
    }
}