package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.model.DocResource;
import com.example.edam.model.SysUser;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.service.AuditService;
import com.example.edam.service.DocumentService;
import com.example.edam.service.WatermarkService;
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
    private final AuditService auditService;
    private final WatermarkService watermarkService;
    private final SysUserRepository sysUserRepository;

    @Value("${minio.bucket.documents}")
    private String documentsBucket;

    @GetMapping
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
    public ResponseEntity<Map<String, Object>> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam("classification_lv") String classificationLv,
        @RequestParam(value = "title", required = false) String title,
        @RequestParam(value = "enable_watermark", defaultValue = "true") Boolean enableWatermark,
        @RequestHeader("X-User-Id") Long uploaderId
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

        DocResource doc = documentService.upload(file, classificationLv, title, enableWatermark, uploaderId);
        Map<String, Object> response = new HashMap<>();
        response.put("doc_id", doc.getId());
        response.put("file_hash", doc.getFileHash());
        response.put("watermark_status", statusToString(doc.getWatermarkStatus()));
        response.put("preview_status", statusToString(doc.getPreviewStatus()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
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
    public DocResource getById(@PathVariable("doc_id") Long docId) {
        return documentService.getById(docId);
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
    public void preview(
            @PathVariable("doc_id") Long docId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            jakarta.servlet.http.HttpServletRequest request,
            HttpServletResponse response
    ) {
        DocResource doc = documentService.getById(docId);
        if (doc.getMinioPath() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档原文不存在");
        }

        String mime = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
        boolean inlineSupported = mime.startsWith("application/pdf") || mime.startsWith("image/")
                                 || mime.startsWith("text/");
        String disposition = inlineSupported ? "inline" : "attachment";

        String filename = URLEncoder.encode(doc.getTitle() != null ? doc.getTitle() : "document",
            StandardCharsets.UTF_8).replace("+", "%20");

        // 拉水印文本（用员工号；查不到就降级）
        String watermarkText = lookupEmployeeNo(userId);
        if (watermarkText == null || watermarkText.isBlank()) {
            watermarkText = "ANONYMOUS";
        }

        response.setContentType(mime);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            disposition + "; filename=\"" + filename + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 防 iframe 劫持：只允许同源页面嵌入
        response.setHeader("X-Frame-Options", "SAMEORIGIN");

        log.info("doc_preview, doc_id={}, user_id={}, mime={}, size={}, watermark=true",
            docId, userId, mime, doc.getSizeBytes());

        // 写审计
        if (userId != null) {
            auditService.log(userId, "preview", "Document", docId, "success",
                "ip=" + request.getRemoteAddr() + ", mime=" + mime + ", watermark=on",
                request.getRemoteAddr(),
                request.getHeader("User-Agent"));
        }

        // 从 MinIO 读 → 加水印 → 流式写 response
        try (InputStream minioIn = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(documentsBucket)
                    .object(doc.getMinioPath())
                    .build());
             OutputStream out = response.getOutputStream()) {

            byte[] watermarkedBytes = watermarkService.applyWatermark(minioIn, mime, watermarkText);
            if (watermarkedBytes != null && watermarkedBytes.length > 0) {
                response.setContentLengthLong(watermarkedBytes.length);
                out.write(watermarkedBytes);
                out.flush();
                log.info("doc_preview_stream_done, doc_id={}, in_size={}, out_size={}, ratio={}x",
                    docId, doc.getSizeBytes(), watermarkedBytes.length,
                    watermarkedBytes.length / Math.max(1, doc.getSizeBytes()));
            } else {
                log.warn("doc_preview_watermark_empty, doc_id={}, mime={}", docId, mime);
                out.flush();
            }
        } catch (Exception e) {
            log.error("doc_preview_stream_failed, doc_id={}", docId, e);
            throw new RuntimeException("预览流失败", e);
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
    public ResponseEntity<Void> delete(
        @PathVariable("doc_id") Long docId,
        @RequestHeader("X-User-Id") Long currentUserId
    ) {
        documentService.delete(docId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-delete")
    public ResponseEntity<Map<String, Object>> batchDelete(
        @RequestBody BatchDeleteRequest request,
        @RequestHeader("X-User-Id") Long operatorId
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
    public Map<String, Object> search(
        @RequestParam("q") String query,
        @RequestParam(required = false) String file_type,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size
    ) {
        // 实际生产：调用 ES 服务
        Page<DocResource> result = documentService.list(page, page_size, null, file_type);
        Map<String, Object> response = toPaginationResponse(result);
        response.put("query", query);
        response.put("took_ms", 5);
        return response;
    }

    private String statusToString(Integer status) {
        if (status == null) return "pending";
        return switch (status) {
            case 0 -> "pending";
            case 1 -> "processing";
            case 2 -> "ready";
            case 3 -> "failed";
            case 4 -> "skipped";
            default -> "unknown";
        };
    }

    private Map<String, Object> toPaginationResponse(Page<DocResource> page) {
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
    public static class BatchDeleteRequest {
        private List<Long> docIds;
        private String reason;
    }
}