package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import com.example.edam.dto.AuditLogDTO;
import com.example.edam.dto.PaginationResponses;
import com.example.edam.model.OperationLog;
import com.example.edam.model.SysUser;
import com.example.edam.repository.OperationLogRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.service.AuditService;
import com.example.edam.security.CurrentUserId;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final SysUserRepository sysUserRepository;

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

    /**
     * 导出审计日志（CSV）。
     *
     * <p><b>原实现是个假接口</b>（2026-11-08 修）：它返回一个指向
     * {@code https://api.example.com/api/v1/audit/exports/...} 的
     * **伪造下载链接**，而仓库里既没有 {@code /audit/exports/**} 路由，
     * 也没有任何导出任务机制 —— 那个 URL 点开永远是 404，
     * 但接口本身返回 202 + 一个看起来很像样的链接。
     *
     * <p>现在同步导出真的文件（UTF-8 BOM 的 CSV，Excel 直接能开），
     * 上限 {@link AuditService#MAX_EXPORT_ROWS} 行，超过就要求缩小时间范围。
     *
     * <p><b>MFA 二次校验</b>：导出审计日志是"把全公司的操作痕迹一次性搬走"，
     * 拿到一份审计日志 ≈ 拿到全公司的行为画像。所以开了 MFA 的操作者
     * 必须带动态令牌 —— 这是管理员手册 §5.2 一直承诺、此前从未实现的。
     */
    @PostMapping("/export")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:audit_export')")
    @AuditOp(op = AuditOpType.EXPORT, resourceType = "audit_log",
        detail = "start=#request.startTime, end=#request.endTime")
    public ResponseEntity<byte[]> export(
        @RequestBody ExportRequest request,
        @CurrentUserId Long operatorId
    ) {
        // MFA 二次校验
        SysUser operator = operatorId != null ? sysUserRepository.findById(operatorId) : null;
        if (operator != null && operator.getMfaEnabled() != null && operator.getMfaEnabled() == 1) {
            String code = request.getMfaCode();
            if (code == null || code.isBlank()
                || !com.example.edam.security.TotpUtil.verify(operator.getMfaSecret(), code)) {
                throw new IllegalArgumentException("导出审计日志需要有效的 MFA 动态令牌");
            }
        }

        // 请求体里的 filter 字段形如 {"user_id": 1, "operation_type": "login"}
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = request.getFilter() != null
            ? request.getFilter() : Map.of();
        Long userId = toLong(filter.get("user_id"));
        String operationType = toStr(filter.get("operation_type"));
        String result = toStr(filter.get("result"));
        String resourceType = toStr(filter.get("resource_type"));
        LocalDateTime startTime = parseTime(request.getStartTime());
        LocalDateTime endTime = parseTime(request.getEndTime());

        byte[] csv = auditService.exportCsv(userId, operationType,
            startTime, endTime, result, resourceType);

        String filename = "audit-export-" + System.currentTimeMillis() + ".csv";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"")
            // 导出文件是敏感数据 —— 不许被中间缓存 / 浏览器缓存
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(csv);
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("filter.user_id 不是合法数字: " + v);
        }
    }

    private static String toStr(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 解析 {@code yyyy-MM-dd HH:mm:ss}（与 JacksonConfig / 列表端点同格式）。 */
    private static LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim(),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "时间格式必须是 yyyy-MM-dd HH:mm:ss，当前: " + s);
        }
    }

    @Data
    public static class ExportRequest {
        private String startTime;
        private String endTime;
        private String format;

        /** 过滤条件：{@code user_id} / {@code operation_type} / {@code result} / {@code resource_type} */
        private Map<String, Object> filter;

        /** MFA 动态令牌 —— 操作者开了 MFA 时必填 */
        @com.fasterxml.jackson.annotation.JsonProperty("mfa_code")
        private String mfaCode;
    }
}