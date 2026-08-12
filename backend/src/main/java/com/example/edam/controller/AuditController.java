package com.example.edam.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.model.OperationLog;
import com.example.edam.repository.OperationLogRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public Map<String, Object> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size,
        @RequestParam(required = false) Long user_id,
        @RequestParam(required = false) String operation_type,
        @RequestParam(required = false) String start_time,
        @RequestParam(required = false) String end_time
    ) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        if (user_id != null) {
            wrapper.eq(OperationLog::getUserId, user_id);
        }
        if (operation_type != null) {
            wrapper.eq(OperationLog::getOperationType, operation_type);
        }
        if (start_time != null) {
            wrapper.ge(OperationLog::getTimestamp, java.time.OffsetDateTime.parse(start_time));
        }
        if (end_time != null) {
            wrapper.le(OperationLog::getTimestamp, java.time.OffsetDateTime.parse(end_time));
        }
        wrapper.orderByDesc(OperationLog::getTimestamp);

        Page<OperationLog> result = operationLogRepository.selectPage(
            new Page<>(page, page_size), wrapper);

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
    public ResponseEntity<Map<String, Object>> export(
        @RequestBody ExportRequest request,
        @RequestHeader("X-User-Id") Long operatorId
    ) {
        // 实际生产：触发异步导出任务 + 写入 download URL
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