package com.example.edam.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.dto.ResourceEnums;
import com.example.edam.dto.PaginationResponses;
import com.example.edam.model.DistributionApproval;
import com.example.edam.model.DocResource;
import com.example.edam.repository.DistributionApprovalRepository;
import com.example.edam.service.DocPermissionService;
import com.example.edam.service.DocumentService;
import com.example.edam.security.CurrentUser;
import com.example.edam.security.CurrentUserId;
import lombok.Data;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
    private final DocumentService documentService;
    private final DocPermissionService docPermissionService;

    /**
     * 当前用户能不能看到**全部**外发申请。
     *
     * <p>有审批权（{@code distribution:approve}）或管理员 ⇒ 全量；
     * 否则只能看自己提交的申请。
     *
     * <p>用 {@link CurrentUser#hasAuthority} 而不是查库 ——
     * 权限码已经签进 JWT claims，不必为了一次列表查询再打一次 DB。
     */
    private boolean canSeeAllApprovals() {
        return CurrentUser.isAdmin()
            || CurrentUser.hasAuthority("distribution:approve");
    }

    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('distribution:read')")
    @AuditOp(op = AuditOpType.LIST, resourceType = "distribution_approval")
    public Map<String, Object> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long applicant_id,
        @RequestParam(required = false) Long approver_id,
        @CurrentUserId Long currentUserId
    ) {
        LambdaQueryWrapper<DistributionApproval> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(DistributionApproval::getStatus, parseStatus(status));
        }
        // 可见范围：**没有审批权的人只能看自己提交的申请**。
        // 原先端点对所有登录用户敞开且不按申请人过滤 —— 等于把全公司
        // 的外发申请（外部接收人邮箱、有效期、理由）暴露给任何普通员工。
        if (canSeeAllApprovals()) {
            if (applicant_id != null) {
                wrapper.eq(DistributionApproval::getApplicantId, applicant_id);
            }
        } else {
            wrapper.eq(DistributionApproval::getApplicantId, currentUserId);
        }
        wrapper.orderByDesc(DistributionApproval::getCreatedAt);
        Page<DistributionApproval> result = approvalRepository.selectPage(new Page<>(page, page_size), wrapper);
        return toPaginationResponse(result);
    }

    @PostMapping
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('distribution:read')")
    @AuditOp(op = AuditOpType.CREATE, resourceType = "distribution_approval",
        detail = "doc_id=#request.docId, recipient_org=#request.externalRecipient?.org")
    public ResponseEntity<Map<String, Object>> create(
        @RequestBody CreateApprovalRequest request,
        @CurrentUserId Long applicantId
    ) {
        // 外发申请必须针对**自己看得见的文档**。
        // 原先端点不校验 doc_id 的归属：任何登录用户都能对任意 doc_id
        // 提交外发申请（包括 L4 绝密），审批人再从申请单里看到外部接收人。
        if (request.getDocId() == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "detail", "doc_id 不能为空"));
        }
        DocResource doc = documentService.getById(request.getDocId());
        if (!docPermissionService.canView(doc, applicantId)) {
            log.warn("distribution_apply_denied, doc_id={}, applicant_id={}, classification_lv={}",
                request.getDocId(), applicantId, doc.getClassificationLv());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden",
                    "detail", "没有该文档的查看权限，不能申请外发"));
        }

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
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('distribution:read')")
    @AuditOp(op = AuditOpType.DETAIL, resourceType = "distribution_approval",
        resourceId = "#approvalId")
    public Map<String, Object> getById(@PathVariable("approval_id") Long approvalId,
                                       @CurrentUserId Long currentUserId) {
        DistributionApproval approval = approvalRepository.selectById(approvalId);
        if (approval == null) {
            throw new ResourceNotFoundException("审批不存在: " + approvalId);
        }
        // 只有申请人本人（或有审批权的人）能看这条申请
        if (!canSeeAllApprovals()
            && !currentUserId.equals(approval.getApplicantId())) {
            log.warn("distribution_detail_denied, approval_id={}, user_id={}",
                approvalId, currentUserId);
            throw new AccessDeniedException("无权查看他人的外发申请: " + approvalId);
        }
        return toResponse(approval);
    }

    @PostMapping("/{approval_id}/decide")
    @Transactional
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "distribution_approval",
        resourceId = "#approvalId", detail = "decision=#request.decision")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('distribution:approve')")
    public ResponseEntity<Map<String, Object>> decide(
        @PathVariable("approval_id") Long approvalId,
        @RequestBody DecideRequest request,
        @CurrentUserId Long approverId
    ) {
        DistributionApproval approval = approvalRepository.selectById(approvalId);
        if (approval == null) {
            throw new ResourceNotFoundException("审批不存在: " + approvalId);
        }

        // 0=pending, 1=approved, 2=rejected
        // decision 可能为 null —— 原来直接 .equals 会 NPE，
        // 而且任何非 "approve" 的值都会被当成 reject（拼错也静默拒绝）
        String decision = request.getDecision();
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            throw new IllegalArgumentException("decision 必须是 approve 或 reject，当前: " + decision);
        }
        int newStatus = "approve".equals(decision) ? 1 : 2;
        approval.setStatus(newStatus);
        approval.setFinalDecisionAt(OffsetDateTime.now());
        approvalRepository.updateById(approval);

        log.info("approval_decided, approval_id={}, decision={}, approver_id={}",
            approvalId, decision, approverId);

        Map<String, Object> response = new HashMap<>();
        response.put("status", ResourceEnums.approvalStatus(approval.getStatus()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{approval_id}/revoke")
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "distribution_approval",
        resourceId = "#approvalId", detail = "action=revoke")
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('distribution:approve')")
    public ResponseEntity<Void> revoke(
        @PathVariable("approval_id") Long approvalId,
        @CurrentUserId Long operatorId
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
        response.put("status", ResourceEnums.approvalStatus(a.getStatus()));
        response.put("current_open_count", a.getCurrentOpenCount());
        response.put("created_at", a.getCreatedAt());
        response.put("decided_at", a.getFinalDecisionAt());
        return response;
    }

    private Map<String, Object> toPaginationResponse(Page<DistributionApproval> page) {
        // 统一分页壳（见 dto/PaginationResponses）—— 逐条转响应体
        return PaginationResponses.of(page, this::toResponse);
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