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
 *
 * <p><b>读端点也必须有 {@code audit:read} 门（2026-11-07 补）</b>：
 * 原先只有导出端点设了门，列表 / 详情 / 两个筛选清单对所有登录用户敞开。
 * 审计日志里装着全公司的操作痕迹 —— 谁在哪个 IP、用什么 UA、
 * 预览了哪个 L4 文档、外发给了哪个外部邮箱 —— 这本身就是最敏感的数据，
 * 而"能看审计日志"必须是一个**显式授予**的能力。
 * 前端菜单（MainLayout.vue）早就按 {@code audit:read} 过滤了「审计日志」入口，
 * 缺的只是后端这一半；补上门后前后端口径一致。
 * 权限目录里 {@code audit:read} 本来就存在，dept_manager / auditor 都已授权，
 * 补门不影响既有角色。
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
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:read')")
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
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:read')")
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
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:read')")
    @AuditOp(op = AuditOpType.LIST, resourceType = "audit_log")
    public Map<String, Object> resourceTypes() {
        Map<String, Object> response = new HashMap<>();
        response.put("resource_types", auditService.distinctResourceTypes());
        return response;
    }

    @GetMapping("/{log_id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:read')")
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