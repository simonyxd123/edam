package com.example.edam.controller;

import com.example.edam.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * 文档预览 Controller（v3.2 V-1 补全）
 * 对应 openapi.yaml tag: preview
 */
@Slf4j
@RestController
@RequestMapping("/preview")
@RequiredArgsConstructor
@Tag(name = "preview", description = "文档预览")
public class PreviewController {

    private final DocumentService documentService;

    @GetMapping("/{doc_id}")
    @Operation(summary = "文档预览（含 Canvas 动态明水印）")
    public ResponseEntity<String> preview(
            @PathVariable("doc_id") Long docId,
            @RequestParam(required = false, defaultValue = "html") String format,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {

        log.info("doc_preview doc_id={} format={} user_id={}", docId, format, userId);
        // v3.2 占位实现：返回 HTML 含 Canvas 水印（实际由前端渲染）
        String html = """
            <!DOCTYPE html>
            <html><head><title>EDAM 文档预览</title></head>
            <body>
              <div id="watermark" style="position:fixed;top:50%%;left:50%%;transform:translate(-50%%,-50%%);opacity:0.3;font-size:48px;color:#888;pointer-events:none;">
                USER_%d - %s
              </div>
              <div id="content"><p>文档内容占位（doc_id=%d）</p></div>
            </body></html>
            """.formatted(userId != null ? userId : 0, java.time.OffsetDateTime.now(), docId);

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header("X-Watermark-Enabled", "true")
            .body(html);
    }

    @GetMapping("/{doc_id}/download")
    @Operation(summary = "下载已加密文档")
    public ResponseEntity<ByteArrayResource> download(
            @PathVariable("doc_id") Long docId,
            @RequestHeader("X-User-Id") Long userId) {

        log.info("doc_download doc_id={} user_id={}", docId, userId);
        // v3.2 占位实现：返回加密文档字节流（实际从 MinIO 获取）
        byte[] encrypted = ("ENCRYPTED_DOC_" + docId).getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(encrypted);

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=doc_" + docId + ".enc")
            .header("X-Encryption", "AES-256-GCM")
            .body(resource);
    }
}