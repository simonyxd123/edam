package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.dto.AuditLogDTO;
import com.example.edam.model.OperationLog;
import com.example.edam.repository.OperationLogRepository;
import com.example.edam.service.AuditService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
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
     */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int page_size,
            @RequestParam(required = false) Long user_id,
            @RequestParam(required = false) String operation_type,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time
    ) {
        Page<AuditLogDTO> result = auditService.list(
            page, page_size, user_id, operation_type, start_time, end_time);

        Map<String, Object> response = new HashMap<>();
        response.put("items", result.getRecords());
        response.put("pagination", Map.of(
            "page", (int) result.getCurrent(),
            "page_size", (int) result.getSize(),
            "total", result.getTotal(),
            "total_pages", (int) result.getPages()
        ));
        return response;
    }

    @GetMapping("/{log_id}")
    public OperationLog getById(@PathVariable("log_id") Long logId) {
        return operationLogRepository.selectById(logId);
    }

    @PostMapping("/export")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:audit_export')")
    public ResponseEntity<Map<String, Object>> export(
            @RequestBody ExportRequest request,
            @RequestHeader("X-User-Id") Long operatorId
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