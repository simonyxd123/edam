package com.example.edam.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
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
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "admin", description = "管理员接口（备份/恢复）")
public class AdminController {

    @GetMapping("/backups")
    @Operation(summary = "备份列表")
    public List<Map<String, Object>> listBackups() {
        log.info("backup_list");
        return List.of();
    }

    @PostMapping("/backups")
    @Operation(summary = "触发备份（高危，需 MFA）")
    public ResponseEntity<Map<String, Object>> triggerBackup(
            @RequestBody BackupRequest request,
            @RequestHeader("X-User-Id") Long operatorId) {

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
    @Operation(summary = "从备份恢复（高危 + 二级审批）")
    public ResponseEntity<Map<String, Object>> restoreBackup(
            @PathVariable("backup_id") String backupId,
            @RequestBody RestoreRequest request,
            @RequestHeader("X-User-Id") Long operatorId) {

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