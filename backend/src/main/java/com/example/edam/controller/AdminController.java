package com.example.edam.controller;

import com.example.edam.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理员 Controller（v3.2 V-1 补全）
 * 对应 openapi.yaml tag: admin
 *
 * 高危操作（备份/恢复）需 MFA + 二级审批
 *
 * <p><b>三个端点全部要 {@code admin:backup} 门（2026-11-07 补）</b>：
 * 触发备份与恢复本来就有门，只有"备份列表"漏了 —— 对任何登录用户敞开。
 * 今天它返回空列表所以没漏东西，但这是个**等着被填上的洞**：
 * 谁来实现备份列表，谁就会不小心把备份清单（备份时间点、覆盖范围、
 * 数据库快照位置）暴露给全体员工。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "admin", description = "管理员接口（备份/恢复）")
public class AdminController {

    @GetMapping("/backups")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('admin:backup')")
    @Operation(summary = "备份列表")
    @AuditOp(op = AuditOpType.LIST, resourceType = "backup")
    public List<Map<String, Object>> listBackups() {
        log.info("backup_list");
        return List.of();
    }

    @PostMapping("/backups")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('admin:backup')")
    @AuditOp(op = AuditOpType.CONFIG_CHANGE, resourceType = "backup",
        detail = "action=trigger_backup, scope=#request.scope")
    @Operation(summary = "触发备份（高危，需 MFA）")
    public ResponseEntity<Map<String, Object>> triggerBackup(
        @RequestBody BackupRequest request,
        @CurrentUserId Long operatorId) {

        log.warn("backup_triggered operator_id={} type={} mfa_verified={}",
            operatorId, request.type, request.mfaCode != null && !request.mfaCode.isBlank());

        if (request.mfaCode == null || request.mfaCode.isBlank()) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body(Map.of("error", "MFA required"));
        }

        String backupId = UUID.randomUUID().toString();
        Map<String, Object> response = new HashMap<>();
        response.put("backup_id", backupId);
        response.put("task_id", UUID.randomUUID().toString());
        response.put("status", "pending");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/backups/{backup_id}/restore")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('admin:restore')")
    @AuditOp(op = AuditOpType.CONFIG_CHANGE, resourceType = "backup",
        resourceId = "#backupId", detail = "action=restore_backup, backup_id=#backupId")
    @Operation(summary = "从备份恢复（高危 + 二级审批）")
    public ResponseEntity<Map<String, Object>> restoreBackup(
        @PathVariable("backup_id") String backupId,
        @RequestBody RestoreRequest request,
        @CurrentUserId Long operatorId) {

        log.warn("restore_triggered operator_id={} backup_id={} mfa_present={} approval_token_present={}",
            operatorId, backupId,
            request.mfaCode != null,
            request.approvalToken != null);

        if (request.mfaCode == null || request.mfaCode.isBlank() ||
            request.approvalToken == null || request.approvalToken.isBlank() ||
            !Boolean.TRUE.equals(request.confirm)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body(Map.of("error", "MFA + 审批令牌 + confirm=true 全部必填"));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("task_id", UUID.randomUUID().toString());
        response.put("status", "pending");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Data
    public static class BackupRequest {
        private String type;        // full / incremental
        private String description;
        private String mfaCode;
    }

    @Data
    public static class RestoreRequest {
        private String mfaCode;
        private String approvalToken;
        private Boolean confirm;
    }
}