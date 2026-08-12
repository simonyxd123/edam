package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.model.DocResource;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.service.DocumentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档资源 Controller
 * 对应 openapi.yaml tag: documents
 */
@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocResourceRepository docRepository;

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
        DocResource doc = documentService.upload(file, classificationLv, title, enableWatermark, uploaderId);
        Map<String, Object> response = new HashMap<>();
        response.put("doc_id", doc.getId());
        response.put("file_hash", doc.getFileHash());
        response.put("watermark_status", statusToString(doc.getWatermarkStatus()));
        response.put("preview_status", statusToString(doc.getPreviewStatus()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{doc_id}")
    public DocResource getById(@PathVariable("doc_id") Long docId) {
        return documentService.getById(docId);
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