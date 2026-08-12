package com.example.edam.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.DistributionApproval;
import com.example.edam.repository.DistributionApprovalRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 外发审批 Controller
 * 对应 openapi.yaml tag: distribution
 */
@Slf4j
@RestController
@RequestMapping("/distribution/approvals")
@RequiredArgsConstructor
public class DistributionController {

    private final DistributionApprovalRepository approvalRepository;

    @GetMapping
    public Map<String, Object> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long applicant_id,
        @RequestParam(required = false) Long approver_id
    ) {
        LambdaQueryWrapper<DistributionApproval> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(DistributionApproval::getStatus, parseStatus(status));
        }
        if (applicant_id != null) {
            wrapper.eq(DistributionApproval::getApplicantId, applicant_id);
        }
        wrapper.orderByDesc(DistributionApproval::getCreatedAt);
        Page<DistributionApproval> result = approvalRepository.selectPage(new Page<>(page, page_size), wrapper);
        return toPaginationResponse(result);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(
        @RequestBody CreateApprovalRequest request,
        @RequestHeader("X-User-Id") Long applicantId
    ) {
        DistributionApproval approval = new DistributionApproval();
        approval.setDocId(request.getDocId());
        approval.setApplicantId(applicantId);
        approval.setExternalRecipientName(request.getExternalRecipient().getName());
        approval.setExternalRecipientEmail(request.getExternalRecipient().getEmail());
        approval.setExternalRecipientOrg(request.getExternalRecipient().getOrg());
        approval.setReason(request.getReason());
        approval.setValidHours(request.getValidHours());
        approval.setMaxOpenCount(request.getMaxOpenCount() != null ? request.getMaxOpenCount() : 5);
        approval.setAllowForward(request.getAllowForward() != null && request.getAllowForward());
        approval.setAllowPrint(request.getAllowPrint() != null && request.getAllowPrint());
        approval.setStatus(0);  // pending
        approval.setCurrentOpenCount(0);
        approvalRepository.insert(approval);

        log.info("approval_created, approval_id={}, applicant_id={}, doc_id={}",
            approval.getId(), applicantId, request.getDocId());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(approval));
    }

    @GetMapping("/{approval_id}")
    public Map<String, Object> getById(@PathVariable("approval_id") Long approvalId) {
        DistributionApproval approval = approvalRepository.selectById(approvalId);
        if (approval == null) {
            throw new ResourceNotFoundException("审批不存在: " + approvalId);
        }
        return toResponse(approval);
    }

    @PostMapping("/{approval_id}/decide")
    @Transactional
    public ResponseEntity<Map<String, Object>> decide(
        @PathVariable("approval_id") Long approvalId,
        @RequestBody DecideRequest request,
        @RequestHeader("X-User-Id") Long approverId
    ) {
        DistributionApproval approval = approvalRepository.selectById(approvalId);
        if (approval == null) {
            throw new ResourceNotFoundException("审批不存在: " + approvalId);
        }

        // 0=pending, 1=approved, 2=rejected
        int newStatus = request.getDecision().equals("approve") ? 1 : 2;
        approval.setStatus(newStatus);
        approval.setFinalDecisionAt(OffsetDateTime.now());
        approvalRepository.updateById(approval);

        log.info("approval_decided, approval_id={}, decision={}, approver_id={}",
            approvalId, request.getDecision(), approverId);

        Map<String, Object> response = new HashMap<>();
        response.put("status", statusToString(approval.getStatus()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{approval_id}/revoke")
    @Transactional
    public ResponseEntity<Void> revoke(
        @PathVariable("approval_id") Long approvalId,
        @RequestHeader("X-User-Id") Long operatorId
    ) {
        DistributionApproval approval = approvalRepository.selectById(approvalId);
        if (approval == null) {
            throw new ResourceNotFoundException("审批不存在: " + approvalId);
        }

        // 4=revoked
        approval.setStatus(4);
        approval.setRevokedBy(operatorId);
        approval.setRevokedAt(OffsetDateTime.now());
        approval.setRevokeReason("紧急撤销 by operator " + operatorId);
        approvalRepository.updateById(approval);

        log.warn("approval_revoked, approval_id={}, operator_id={}", approvalId, operatorId);
        return ResponseEntity.noContent().build();
    }

    private Integer parseStatus(String s) {
        return switch (s) {
            case "pending" -> 0;
            case "approved" -> 1;
            case "rejected" -> 2;
            case "expired" -> 3;
            case "revoked" -> 4;
            default -> -1;
        };
    }

    private String statusToString(Integer status) {
        if (status == null) return "pending";
        return switch (status) {
            case 0 -> "pending";
            case 1 -> "approved";
            case 2 -> "rejected";
            case 3 -> "expired";
            case 4 -> "revoked";
            default -> "unknown";
        };
    }

    private Map<String, Object> toResponse(DistributionApproval a) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", a.getId());
        response.put("doc_id", a.getDocId());
        response.put("applicant_id", a.getApplicantId());
        response.put("external_recipient", Map.of(
            "name", a.getExternalRecipientName() != null ? a.getExternalRecipientName() : "",
            "email", a.getExternalRecipientEmail() != null ? a.getExternalRecipientEmail() : "",
            "org", a.getExternalRecipientOrg() != null ? a.getExternalRecipientOrg() : ""
        ));
        response.put("reason", a.getReason());
        response.put("valid_hours", a.getValidHours());
        response.put("max_open_count", a.getMaxOpenCount());
        response.put("allow_forward", a.getAllowForward() != null && a.getAllowForward());
        response.put("allow_print", a.getAllowPrint() != null && a.getAllowPrint());
        response.put("status", statusToString(a.getStatus()));
        response.put("current_open_count", a.getCurrentOpenCount());
        response.put("created_at", a.getCreatedAt());
        response.put("decided_at", a.getFinalDecisionAt());
        return response;
    }

    private Map<String, Object> toPaginationResponse(Page<DistributionApproval> page) {
        Map<String, Object> response = new HashMap<>();
        response.put("items", page.getRecords());
        response.put("pagination", Map.of(
            "page", (int) page.getCurrent(),
            "page_size", (int) page.getSize(),
            "total", page.getTotal(),
            "total_pages", (int) page.getPages()
        ));
        return response;
    }

    @Data
    public static class CreateApprovalRequest {
        private Long docId;
        private Recipient externalRecipient;
        private String reason;
        private Integer validHours;
        private Integer maxOpenCount;
        private Boolean allowForward;
        private Boolean allowPrint;
    }

    @Data
    public static class Recipient {
        private String name;
        private String email;
        private String org;
    }

    @Data
    public static class DecideRequest {
        private String decision;
        private String comment;
    }
}