package com.example.edam.controller;

import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import com.example.edam.security.CurrentUserId;
import com.example.edam.security.classification.ClassificationEnforcement;
import com.example.edam.security.classification.ClassificationLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 密级人工调整（v3.4.1）
 *
 * <p>密级在**上传时**由服务端裁决，规则是"只能往严里选"——
 * 客户端声明的密级不能低于服务端基线。这是一道**单向棘轮**：
 * 一个被自动识别成 L3 的文件，光靠上传流程永远降不下来。
 *
 * <p>所以这里提供唯一能把密级往低调的入口：
 * <ul>
 *   <li>门是 {@code classification:manage}（权限目录里已有，此前 Java 侧零引用）</li>
 *   <li>每次调整都写 {@code data_classification_audit}（谁、何时、从什么改成什么、为什么）</li>
 *   <li>调整原因必填 —— 没有理由的降级等于审计形同虚设</li>
 * </ul>
 *
 * <p>方案书对应章节：5.4 细粒度权限与外发管控、6.3 密级模型。
 */
@Slf4j
@RestController
@RequestMapping("/classification")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('classification:manage')")
public class ClassificationController {

    private final ClassificationEnforcement classificationEnforcement;

    /**
     * 调整视频密级。
     */
    @PatchMapping("/videos/{video_id}")
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "video",
        resourceId = "#videoId",
        detail = "classification_lv=#request.classificationLv, reason=#request.reason")
    public ResponseEntity<Map<String, Object>> updateVideoClassification(
            @PathVariable("video_id") Long videoId,
            @RequestBody ClassificationChangeRequest request,
            @CurrentUserId Long operatorId) {

        ClassificationLevel level = ClassificationLevel.parseStrict(request.getClassificationLv());
        if (level == null) {
            throw new IllegalArgumentException("classification_lv 不能为空");
        }
        String reason = requireReason(request.getReason());

        classificationEnforcement.changeClassification(
            "video", videoId, level, operatorId, reason);

        Map<String, Object> response = new HashMap<>();
        response.put("resource_type", "video");
        response.put("resource_id", videoId);
        response.put("classification_lv", level.getCode());
        return ResponseEntity.ok(response);
    }

    /**
     * 调整文档密级。
     */
    @PatchMapping("/documents/{doc_id}")
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "document",
        resourceId = "#docId",
        detail = "classification_lv=#request.classificationLv, reason=#request.reason")
    public ResponseEntity<Map<String, Object>> updateDocClassification(
            @PathVariable("doc_id") Long docId,
            @RequestBody ClassificationChangeRequest request,
            @CurrentUserId Long operatorId) {

        ClassificationLevel level = ClassificationLevel.parseStrict(request.getClassificationLv());
        if (level == null) {
            throw new IllegalArgumentException("classification_lv 不能为空");
        }
        String reason = requireReason(request.getReason());

        classificationEnforcement.changeClassification(
            "document", docId, level, operatorId, reason);

        Map<String, Object> response = new HashMap<>();
        response.put("resource_type", "document");
        response.put("resource_id", docId);
        response.put("classification_lv", level.getCode());
        return ResponseEntity.ok(response);
    }

    /**
     * 变更原因必填 —— 降级没有理由就等于审计形同虚设。
     */
    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("变更密级必须填写 reason");
        }
        return reason.trim();
    }

    /**
     * 密级变更请求体。
     */
    @Data
    public static class ClassificationChangeRequest {
        /** 目标密级（L1 / L2 / L3 / L4） */
        private String classificationLv;
        /** 变更原因（必填，进审计） */
        private String reason;
    }
}
