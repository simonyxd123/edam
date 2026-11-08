package com.example.edam.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.dto.ResourceEnums;
import com.example.edam.dto.PaginationResponses;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.edam.model.DistributionApproval;
import com.example.edam.model.DistributionApprovalDecision;
import com.example.edam.model.DocResource;
import com.example.edam.repository.DistributionApprovalDecisionRepository;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 外发审批 Controller
 * 对应 openapi.yaml tag: distribution
 *
 * <p><b>2026-11-07 审计修掉的问题</b>：
 * <ol>
 *   <li>{@code create()} 直接 {@code request.getExternalRecipient().getName()} ——
 *       请求体里没有 external_recipient 就 NPE → 500；
 *       name / email / reason / valid_hours 也都不校验，
 *       而这四列在库里都是 NOT NULL ⇒ 同样是 500 而不是 400。
 *       现在统一在入口校验，非法请求一律 400。</li>
 *   <li>{@code decide()} 收了 {@code comment} 却**从不保存**，
 *       {@code distribution_approval_decision} 表零引用 ⇒
 *       谁批的、什么时候批的、批语是什么全都查不到。审批是"把资产送到公司外面"
 *       这道闸，闸门每次开合都必须留痕。现在写流水表。</li>
 *   <li>{@code decide()} / {@code revoke()} 没有状态机校验 ——
 *       已驳回的申请能被再"批准"一次，已撤销的能被再撤销。</li>
 *   <li>没有自审自批防护 ——
 *       持有 {@code distribution:approve} 的人可以审批**自己**提交的外发申请。</li>
 * </ol>
 *
 * <p>方案书 5.4 细粒度权限与外发管控。
 */
@Slf4j
@RestController
@RequestMapping("/distribution/approvals")
@RequiredArgsConstructor
public class DistributionController {

    /** 默认外发有效期（小时）。 */
    private static final int DEFAULT_VALID_HOURS = 24;
    /** 有效期上限（小时）= 1 年。 */
    private static final int MAX_VALID_HOURS = 8760;
    /** 默认最大打开次数。 */
    private static final int DEFAULT_MAX_OPEN_COUNT = 5;

    private final DistributionApprovalRepository approvalRepository;
    private final DistributionApprovalDecisionRepository decisionRepository;
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
            throw new IllegalArgumentException("doc_id 不能为空");
        }
        // 入参校验 —— 这些列在库里都是 NOT NULL，不校验就是 500 而不是 400。
        Recipient recipient = request.getExternalRecipient();
        if (recipient == null) {
            throw new IllegalArgumentException("external_recipient 不能为空");
        }
        String recipientName = requireText(recipient.getName(), "external_recipient.name");
        String recipientEmail = requireText(recipient.getEmail(), "external_recipient.email");
        String reason = requireText(request.getReason(), "reason");
        int validHours = request.getValidHours() != null
            ? request.getValidHours() : DEFAULT_VALID_HOURS;
        if (validHours <= 0 || validHours > MAX_VALID_HOURS) {
            throw new IllegalArgumentException(
                "valid_hours 必须在 1 ~ " + MAX_VALID_HOURS + " 之间，当前: " + validHours);
        }
        int maxOpenCount = request.getMaxOpenCount() != null
            ? request.getMaxOpenCount() : DEFAULT_MAX_OPEN_COUNT;
        if (maxOpenCount <= 0) {
            throw new IllegalArgumentException("max_open_count 必须大于 0，当前: " + maxOpenCount);
        }

        DocResource doc = documentService.getById(request.getDocId());
        if (!docPermissionService.canView(doc, applicantId)) {
            log.warn("distribution_apply_denied, doc_id={}, applicant_id={}, classification_lv={}",
                request.getDocId(), applicantId, doc.getClassificationLv());
            throw new AccessDeniedException("没有该文档的查看权限，不能申请外发");
        }

        DistributionApproval approval = new DistributionApproval();
        approval.setDocId(request.getDocId());
        approval.setApplicantId(applicantId);
        approval.setExternalRecipientName(recipientName);
        approval.setExternalRecipientEmail(recipientEmail);
        approval.setExternalRecipientOrg(recipient.getOrg());
        approval.setReason(reason);
        approval.setValidHours(validHours);
        approval.setMaxOpenCount(maxOpenCount);
        approval.setAllowForward(request.getAllowForward() != null && request.getAllowForward());
        approval.setAllowPrint(request.getAllowPrint() != null && request.getAllowPrint());
        approval.setStatus(STATUS_PENDING);  // pending
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

    /**
     * 审批单的**决策流水**（谁批的 / 什么时候批的 / 批语是什么）。
     *
     * <p>主表只留当前状态，这张流水才是完整证据链 —— 供审计追溯用。
     */
    @GetMapping("/{approval_id}/decisions")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('distribution:read')")
    @AuditOp(op = AuditOpType.LIST, resourceType = "distribution_approval_decision",
        resourceId = "#approvalId")
    public Map<String, Object> listDecisions(@PathVariable("approval_id") Long approvalId,
                                             @CurrentUserId Long currentUserId) {
        DistributionApproval approval = approvalRepository.selectById(approvalId);
        if (approval == null) {
            throw new ResourceNotFoundException("审批不存在: " + approvalId);
        }
        if (!canSeeAllApprovals()
            && !currentUserId.equals(approval.getApplicantId())) {
            throw new AccessDeniedException("无权查看他人的外发申请: " + approvalId);
        }
        List<Map<String, Object>> items = decisionRepository.findByApprovalId(approvalId).stream()
            .map(d -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", d.getId());
                item.put("approval_id", d.getApprovalId());
                item.put("approver_id", d.getApproverId());
                item.put("decision", d.getDecision() != null && d.getDecision() == 1
                    ? "approve" : "reject");
                item.put("comment", d.getComment());
                item.put("decided_at", d.getDecidedAt());
                return item;
            })
            .toList();
        Map<String, Object> response = new HashMap<>();
        response.put("approval_id", approvalId);
        response.put("items", items);
        return response;
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

        // 状态机：只有**待审批**的单子能决策。
        // 原先没有这层校验 —— 已驳回的申请能被再"批准"一次，
        // 审批记录也就此对不上账。
        if (approval.getStatus() == null || approval.getStatus() != STATUS_PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "该申请已处理过（当前状态: " + ResourceEnums.approvalStatus(approval.getStatus())
                    + "），不能重复审批");
        }

        // 自审自批防护：申请人不能审批自己的外发申请。
        // 有 distribution:approve 的人如果能给自己开外发口子，
        // 整个审批环节就形同虚设。
        if (approverId != null && approverId.equals(approval.getApplicantId())) {
            log.warn("distribution_self_approval_blocked, approval_id={}, user_id={}",
                approvalId, approverId);
            throw new AccessDeniedException("不能审批自己提交的外发申请");
        }

        // 驳回必须写理由 —— 不然事后追责只剩"被拒了"三个字。
        String comment = request.getComment() != null ? request.getComment().trim() : null;
        if ("reject".equals(decision) && (comment == null || comment.isEmpty())) {
            throw new IllegalArgumentException("驳回时必须填写审批意见");
        }

        int newStatus = "approve".equals(decision) ? STATUS_APPROVED : STATUS_REJECTED;
        approval.setStatus(newStatus);
        approval.setFinalDecisionAt(OffsetDateTime.now());
        approvalRepository.updateById(approval);

        // 决策流水 —— 审批是"把资产送到公司外面"这道闸，
        // 闸门每次开合都必须留痕（谁 / 何时 / 批语）。
        DistributionApprovalDecision record = new DistributionApprovalDecision();
        record.setApprovalId(approvalId);
        record.setApproverId(approverId);
        record.setDecision("approve".equals(decision) ? 1 : 2);
        record.setComment(comment);
        record.setDecidedAt(LocalDateTime.now());
        decisionRepository.insert(record);

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
        @RequestBody(required = false) RevokeRequest request,
        @CurrentUserId Long operatorId
    ) {
        DistributionApproval approval = approvalRepository.selectById(approvalId);
        if (approval == null) {
            throw new ResourceNotFoundException("审批不存在: " + approvalId);
        }

        // 状态机：只有待审批 / 已批准的能被撤销。
        // 已驳回(2) / 已过期(3) / 已撤销(4) 再撤销一次没有意义。
        Integer st = approval.getStatus();
        if (st == null || (st != STATUS_PENDING && st != STATUS_APPROVED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "该申请当前状态无法撤销（" + ResourceEnums.approvalStatus(st) + "）");
        }

        // 4=revoked
        approval.setStatus(STATUS_REVOKED);
        approval.setRevokedBy(operatorId);
        approval.setRevokedAt(OffsetDateTime.now());
        String reason = (request != null && request.getReason() != null
            && !request.getReason().isBlank())
            ? request.getReason().trim()
            : ("紧急撤销 by operator " + operatorId);
        approval.setRevokeReason(reason);
        approvalRepository.updateById(approval);

        log.warn("approval_revoked, approval_id={}, operator_id={}", approvalId, operatorId);
        return ResponseEntity.noContent().build();
    }

    /** 必填文本校验 —— 空串和 null 都算没填。 */
    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private Integer parseStatus(String s) {
        return switch (s) {
            case "pending" -> STATUS_PENDING;
            case "approved" -> STATUS_APPROVED;
            case "rejected" -> STATUS_REJECTED;
            case "expired" -> STATUS_EXPIRED;
            case "revoked" -> STATUS_REVOKED;
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

    // 审批单状态机（与 distribution_approval.status 的取值对齐）
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_APPROVED = 1;
    private static final int STATUS_REJECTED = 2;
    private static final int STATUS_EXPIRED = 3;
    private static final int STATUS_REVOKED = 4;

    /**
     * 发起外发审批的请求体。
     *
     * <p><b>字段名必须显式标 snake_case</b>（2026-11-07 修）：
     * 本仓库没有全局的命名策略 —— 请求 DTO 一律用 {@code @JsonProperty}
     * 逐个标（见 {@code UserController.CreateUserRequest} 等）。
     * 原先这个 DTO 一个都没标，于是前端发的 {@code doc_id} /
     * {@code external_recipient} / {@code valid_hours} 全部**绑不上**，
     * {@code docId} 恒为 null ⇒ 端点恒返回 400。
     * 也就是说「发起外发审批」这个功能在界面上从来没通过过 ——
     * 而且不报错、不进日志，只是安静地失败。
     */
    @Data
    public static class CreateApprovalRequest {
        @JsonProperty("doc_id")
        private Long docId;

        @JsonProperty("external_recipient")
        private Recipient externalRecipient;

        private String reason;

        @JsonProperty("valid_hours")
        private Integer validHours;

        @JsonProperty("max_open_count")
        private Integer maxOpenCount;

        @JsonProperty("allow_forward")
        private Boolean allowForward;

        @JsonProperty("allow_print")
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

    @Data
    public static class RevokeRequest {
        /** 撤销原因（可选；不给则自动生成一条） */
        private String reason;
    }
}
