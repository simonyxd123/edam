package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.dto.ResourceEnums;
import com.example.edam.dto.DocResponse;
import com.example.edam.dto.PaginationResponses;
import com.example.edam.model.DocResource;
import com.example.edam.model.SysUser;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import com.example.edam.service.DocumentPreviewService;
import com.example.edam.service.DocumentService;
import com.example.edam.service.WatermarkService;
import com.example.edam.security.CurrentUserId;
import com.example.edam.security.WorkerTokenVerifier;
import com.example.edam.security.classification.ClassificationLevel;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档资源 Controller
 * 对应 openapi.yaml tag: documents
 */
@RestController
@RequestMapping("/documents")
@Slf4j
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocResourceRepository docRepository;
    private final MinioClient minioClient;
    private final WatermarkService watermarkService;
    private final com.example.edam.service.DocumentPreviewService previewService;
    private final com.example.edam.service.DocPermissionService docPermissionService;
    private final SysUserRepository sysUserRepository;
    private final WorkerTokenVerifier workerTokenVerifier;

    @Value("${minio.bucket.documents}")
    private String documentsBucket;

    @GetMapping
    @AuditOp(op = AuditOpType.LIST, resourceType = "document")
    public Map<String, Object> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size,
        @RequestParam(required = false) String classification_lv,
        @RequestParam(required = false) String file_type
    ) {
        Page<DocResource> result = documentService.list(page, page_size, classification_lv, file_type);
        return toPaginationResponse(result);
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('document:upload')")
    @AuditOp(op = AuditOpType.CREATE, resourceType = "document",
        detail = "title=#title, classification_lv=#classificationLv, watermark=#enableWatermark")
    public ResponseEntity<Map<String, Object>> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam("classification_lv") String classificationLv,
        @RequestParam(value = "title", required = false) String title,
        @RequestParam(value = "enable_watermark", defaultValue = "true") Boolean enableWatermark,
        @CurrentUserId Long uploaderId
    ) {
        // 文件类型校验：仅接受 office / pdf / image
        String contentType = file.getContentType();
        String originalName = file.getOriginalFilename();
        if (!isAllowedDocumentType(contentType, originalName)) {
            throw new DocumentExceptionException(
                "仅支持 Office (Word/Excel/PowerPoint) / PDF / 图片，当前: "
                    + contentType + " / " + originalName);
        }
        if (file.isEmpty()) {
            throw new DocumentExceptionException("文件为空");
        }
        // 50 MB 上限（前端 / 后端兜底）
        long MAX = 50L * 1024 * 1024;
        if (file.getSize() > MAX) {
            throw new DocumentExceptionException("文档不能超过 50 MB");
        }

        // 密级取值必须合法 —— 在**文件还没开始传**的时候就拒掉。
        // 服务端还会再校验一次（防绕过 Controller 直接调 Service），
        // 并且最终密级由服务端裁决器定，客户端只能往严里选。
        ClassificationLevel.parseStrict(classificationLv);

        DocResource doc = documentService.upload(file, classificationLv, title, enableWatermark, uploaderId);
        Map<String, Object> response = new HashMap<>();
        response.put("doc_id", doc.getId());
        response.put("file_hash", doc.getFileHash());
        // 回传**实际落库**的密级 —— 服务端可能把客户端声明的密级抬高了，
        // 前端要据此提示用户，不能让用户以为自己声明的密级生效了
        response.put("classification_lv", "L" + doc.getClassificationLv());
        response.put("watermark_status", ResourceEnums.processStatus(doc.getWatermarkStatus()));
        response.put("preview_status", ResourceEnums.processStatus(doc.getPreviewStatus()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Worker 回调更新文档处理状态。
     *
     * <p><b>鉴权</b>：与视频侧 {@code PATCH /videos/{id}/status} 完全一致 ——
     * 要求带 {@code X-Worker-Token} 头，常量时间比较，后端没配 token 时
     * 一律拒绝（fail-closed）。校验逻辑抽在
     * {@link com.example.edam.security.WorkerTokenVerifier} 里 ——
     * 这类安全关键代码不能复制两份。
     *
     * <p>此前**根本没有这个端点**，Worker 处理完文档也不回调 ——
     * 于是 {@code watermark_status} / {@code preview_status} 永远停在
     * {@code pending}，前端的状态标签恒为 pending，用户永远等不到结果。
     */
    @PatchMapping("/{doc_id}/status")
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "document", resourceId = "#docId",
        detail = "watermark_status=#request.watermarkStatus, preview_status=#request.previewStatus")
    public ResponseEntity<Map<String, Object>> updateStatus(
        @PathVariable("doc_id") Long docId,
        @RequestBody DocStatusUpdateRequest request,
        // 参数名不能叫 workerToken —— 会遮蔽同名字段，鉴权就永远拿配置值比
        @RequestHeader(value = "X-Worker-Token", required = false) String providedWorkerToken
    ) {
        if (!workerTokenVerifier.isValid(providedWorkerToken)) {
            log.warn("doc_status_update_unauthorized, doc_id={}, reason={}",
                docId, workerTokenVerifier.rejectReason(providedWorkerToken));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("doc_status_update_callback, doc_id={}, watermark_status={}, preview_status={}",
            docId, request.getWatermarkStatus(), request.getPreviewStatus());

        documentService.updateProcessingStatus(
            docId,
            request.getWatermarkStatus(), request.getPreviewStatus(),
            request.getWatermarkPath(), request.getPreviewPath()
        );

        DocResource updated = documentService.getById(docId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("doc_id", updated.getId());
        resp.put("watermark_status", ResourceEnums.processStatus(updated.getWatermarkStatus()));
        resp.put("preview_status", ResourceEnums.processStatus(updated.getPreviewStatus()));
        return ResponseEntity.ok(resp);
    }

    private boolean isAllowedDocumentType(String contentType, String filename) {
        // MIME 白名单
        if (contentType != null) {
            String c = contentType.toLowerCase();
            if (c.contains("officedocument") || c.contains("pdf") ||
                c.contains("image/") || c.contains("msword") ||
                c.contains("excel") || c.contains("powerpoint")) {
                return true;
            }
        }
        // 文件后缀白名单（兜底）
        if (filename != null) {
            String f = filename.toLowerCase();
            if (f.endsWith(".pdf") || f.endsWith(".docx") || f.endsWith(".doc") ||
                f.endsWith(".xlsx") || f.endsWith(".xls") ||
                f.endsWith(".pptx") || f.endsWith(".ppt") ||
                f.endsWith(".png") || f.endsWith(".jpg") || f.endsWith(".jpeg") ||
                f.endsWith(".gif") || f.endsWith(".bmp")) {
                return true;
        }
        }
        return false;
    }

    /** 文件类型非法时抛 415（实际是 400，类型简单就不开新异常类了） */
    static class DocumentExceptionException extends RuntimeException {
        DocumentExceptionException(String msg) { super(msg); }
    }

    @ExceptionHandler(DocumentExceptionException.class)
    public ResponseEntity<Map<String, Object>> handleDocException(DocumentExceptionException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "bad_request");
        body.put("detail", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @GetMapping("/{doc_id}")
    @AuditOp(op = AuditOpType.DETAIL, resourceType = "document", resourceId = "#docId")
    public ResponseEntity<?> getById(@PathVariable("doc_id") Long docId,
                                     @CurrentUserId Long currentUserId) {
        // 返回 DTO 而不是实体：实体的状态/密级是 Integer，前端当字符串比较恒不成立
        DocResource doc = documentService.getById(docId);

        // 可见性校验：列表已按可见性过滤，直接用 URL 访问也必须同样拦住
        if (!docPermissionService.canView(doc, currentUserId)) {
            log.warn("doc_detail_denied, doc_id={}, user_id={}, classification_lv={}",
                docId, currentUserId, doc.getClassificationLv());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden",
                    "detail", "没有该文档的查看权限（密级 " + doc.getClassificationLv()
                        + "，需由上传者授权）"));
        }

        return ResponseEntity.ok(DocResponse.from(doc));
    }

    /**
     * 文档预览（流式输出 MinIO 原始字节）
     *
     * GET /documents/{doc_id}/preview
     * - 鉴权：JWT (任意登录用户即可预览，与分发权限解耦)
     * - 审计：写 operation_log (preview)
     * - 返回 Content-Type = 文档 mime，前端用浏览器原生 / iframe 渲染
     *
     * 安全：文件不离开后端，前端拿不到 MinIO 凭据；
     * 真正的防泄密要求服务端 PDF.js 水印 / 文本抽取 / 转图片，本期先做"能看"。
     *
     * 生产建议：加 Range 支持 / ETag / Cache-Control: private
     */
    @GetMapping("/{doc_id}/preview")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('document:read')")
    @AuditOp(op = AuditOpType.PREVIEW, resourceType = "document", resourceId = "#docId",
        detail = "watermark=on")
    public void preview(
        @PathVariable("doc_id") Long docId,
        @CurrentUserId(required = false) Long userId,
            jakarta.servlet.http.HttpServletRequest request,
            HttpServletResponse response
    ) {
        // 走共用的 DocumentPreviewService（与 /preview/{id} 端点同一份实现），
        // 避免两处各写一遍水印 + 流式输出的逻辑而行为漂移
        DocumentPreviewService.PreviewContent content =
            previewService.load(docId, userId, DocumentPreviewService.Access.VIEW);

        response.setContentType(content.mime());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            DocumentPreviewService.contentDisposition(content.inline(), content.filename()));
        DocumentPreviewService.applySecurityHeaders(response);
        response.setHeader("X-Watermark-Enabled", "true");

        log.info("doc_preview, doc_id={}, user_id={}, mime={}, size={}, watermark=true",
            docId, userId, content.mime(), content.bytes().length);


        try {
            response.setContentLengthLong(content.bytes().length);
            response.getOutputStream().write(content.bytes());
            response.getOutputStream().flush();
        } catch (java.io.IOException e) {
            log.error("doc_preview_write_failed, doc_id={}", docId, e);
            throw new RuntimeException("预览流写入失败", e);
        }
    }

    /** 用 userId 查员工号，水印里用 */
    private String lookupEmployeeNo(Long userId) {
        if (userId == null) return null;
        try {
            SysUser u = sysUserRepository.selectById(userId);
            return u != null ? u.getEmployeeNo() : null;
        } catch (Exception e) {
            log.warn("employee_no_lookup_failed, userId={}", userId);
            return null;
        }
    }

    @DeleteMapping("/{doc_id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('document:delete')")
    @AuditOp(op = AuditOpType.DELETE, resourceType = "document", resourceId = "#docId")
    public ResponseEntity<Void> delete(
        @PathVariable("doc_id") Long docId,
        @CurrentUserId Long currentUserId
    ) {
        documentService.delete(docId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-delete")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('document:delete')")
    @AuditOp(op = AuditOpType.BATCH_DELETE, resourceType = "document",
        detail = "count=#request.docIds?.size()")
    public ResponseEntity<Map<String, Object>> batchDelete(
        @RequestBody BatchDeleteRequest request,
        @CurrentUserId Long operatorId
    ) {
        int count = 0;
        for (Long id : request.getDocIds()) {
            try {
                documentService.delete(id, operatorId);
                count++;
            } catch (Exception e) {
                log.warn("batch_delete_skip, doc_id={}, error={}", id, e.getMessage());
            }
        }
        Map<String, Object> response = new HashMap<>();
        response.put("task_id", java.util.UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/search")
    @AuditOp(op = AuditOpType.SEARCH, resourceType = "document")
    public Map<String, Object> search(
        @RequestParam("q") String query,
        @RequestParam(required = false) String file_type,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size
    ) {
        // q 真正参与过滤：此前收了 query 却**完全不用**，返回全量列表。
        // 完整 ES 检索（分词/高亮/相关性）是 v3.2 V-7 的规划，
        // 当前按标题模糊匹配实现。
        Page<DocResource> result = documentService.list(page, page_size, null, file_type, query);
        Map<String, Object> response = toPaginationResponse(result);
        response.put("query", query);
        response.put("took_ms", 5);
        return response;
    }


    private Map<String, Object> toPaginationResponse(Page<DocResource> page) {
        // 统一分页壳（见 dto/PaginationResponses）—— 逐条转 DTO（Integer → String 枚举映射）
        return PaginationResponses.of(page, DocResponse::from);
    }

    /**
     * 批量授权（把文档分享给多个用户）。
     *
     * <p><b>授权模型</b>（依据 {@code doc/database_schema.md} §3.2 与
     * {@code doc/openapi.yaml} 的本端点契约）：
     * <ul>
     *   <li>{@code actions} 位掩码：
     *       {@code 1=view 2=download 4=edit 8=delete 16=distribute 32=print}。</li>
     *   <li>{@code expire_at} 为空 = 永不过期；非空则到期后该条授权自动失效。</li>
     *   <li>再次授权同一用户是<b>覆盖</b>（唯一键 {@code uk_doc_user}）。</li>
     * </ul>
     *
     * <p><b>再分享的约束</b>（方案书 5.4「越权分享自动阻断」）：
     * 只有上传者本人、管理员、或持有 {@code distribute} 位的人才能转授。
     */
    @PostMapping("/batch-permission")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('document:share')")
    @AuditOp(op = AuditOpType.GRANT_PERMISSION, resourceType = "document",
        resourceId = "#request.docId",
        detail = "user_count=#request.userIds?.size(), actions=#request.actions")
    public ResponseEntity<Map<String, Object>> batchPermission(
        @RequestBody BatchPermissionRequest request,
        @CurrentUserId Long currentUserId
    ) {
        List<Long> userIds = request.getUserIds();
        if (userIds == null || userIds.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "detail", "user_ids 不能为空"));
        }
        if (userIds.size() > 200) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "detail", "一次最多授权 200 个用户"));
        }
        Integer actions = request.getActions();
        if (actions == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "detail", "actions 不能为空"));
        }
        DocResource doc = documentService.getById(request.getDocId());

        if (!docPermissionService.canDistribute(doc, currentUserId)) {
            log.warn("doc_permission_distribute_denied, doc_id={}, user_id={}",
                request.getDocId(), currentUserId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden",
                    "detail", "无权分享该文档（需要是上传者、管理员，或持有 distribute 授权）"));
        }

        int granted = docPermissionService.grant(
            request.getDocId(), userIds, actions, request.expireAtOrNull(), currentUserId);

        Map<String, Object> body = new HashMap<>();
        body.put("granted_count", granted);
        body.put("doc_id", request.getDocId());
        body.put("actions", actions);
        body.put("expire_at", request.getExpireAt());
        return ResponseEntity.ok(body);
    }

    /**
     * 撤销授权。
     */
    @DeleteMapping("/batch-permission")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('document:share')")
    @AuditOp(op = AuditOpType.REVOKE_PERMISSION, resourceType = "document",
        resourceId = "#request.docId",
        detail = "user_count=#request.userIds?.size()")
    public ResponseEntity<Map<String, Object>> revokePermission(
        @RequestBody BatchPermissionRequest request,
        @CurrentUserId Long currentUserId
    ) {
        List<Long> userIds = request.getUserIds();
        if (userIds == null || userIds.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "detail", "user_ids 不能为空"));
        }
        DocResource doc = documentService.getById(request.getDocId());
        if (!docPermissionService.canDistribute(doc, currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "detail", "无权撤销该文档的授权"));
        }
        int revoked = docPermissionService.revoke(request.getDocId(), userIds);
        Map<String, Object> body = new HashMap<>();
        body.put("revoked_count", revoked);
        body.put("doc_id", request.getDocId());
        return ResponseEntity.ok(body);
    }

    /**
     * 查询文档当前的授权列表。
     */
    @GetMapping("/batch-permission")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('document:share')")
    @AuditOp(op = AuditOpType.LIST, resourceType = "document_permission",
        resourceId = "#docId")
    public ResponseEntity<Map<String, Object>> listPermission(
        @RequestParam("doc_id") Long docId,
        @CurrentUserId Long currentUserId
    ) {
        DocResource doc = documentService.getById(docId);
        if (!docPermissionService.canDistribute(doc, currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "detail", "无权查看该文档的授权列表"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (com.example.edam.model.DocPermission p : docPermissionService.listByDoc(docId)) {
            Map<String, Object> item = new HashMap<>();
            item.put("user_id", p.getUserId());
            item.put("actions", p.getActions());
            item.put("granted_by", p.getGrantedBy());
            item.put("granted_at", p.getGrantedAt());
            item.put("expire_at", p.getExpireAt());
            items.add(item);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("doc_id", docId);
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    @Data
    public static class BatchDeleteRequest {
        private List<Long> docIds;
        private String reason;
    }

    @Data
    public static class DocStatusUpdateRequest {
        /** 水印状态：0=pending 1=processing 2=ready 3=failed 4=skipped */
        private Integer watermarkStatus;
        /** 预览状态：0=pending 1=processing 2=ready 3=failed 4=skipped */
        private Integer previewStatus;
        /** 水印产物在 MinIO 里的对象名 */
        private String watermarkPath;
        /** 预览产物在 MinIO 里的对象名 */
        private String previewPath;
    }

    @Data
    public static class BatchPermissionRequest {
        @JsonProperty("doc_id")
        private Long docId;
        @JsonProperty("user_ids")
        private List<Long> userIds;
        /** 位掩码：1=view 2=download 4=edit 8=delete 16=distribute 32=print */
        @JsonProperty("actions")
        private Integer actions;
        /** 过期时间（ISO-8601 date-time）；为空 = 永不过期 */
        @JsonProperty("expire_at")
        private String expireAt;

        /** 解析后的过期时间；没给或解析不了就是 null（永不过期） */
        OffsetDateTime expireAtOrNull() {
            if (expireAt == null || expireAt.isBlank()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(expireAt);
            } catch (Exception e) {
                throw new IllegalArgumentException("expire_at 必须是 ISO-8601 时间: " + expireAt, e);
            }
        }
    }
}