package com.example.edam.controller;

import com.example.edam.service.DocumentPreviewService;
import com.example.edam.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 文档预览 Controller（v3.2 V-1 补全）
 * 对应 openapi.yaml tag: preview
 *
 * <p><b>已从占位实现改为真实实现</b>：原实现返回写死的 HTML 字符串和
 * {@code "ENCRYPTED_DOC_<id>"} 字节流 —— 前端以为拿到了文档预览/加密文件，
 * 实际拿到的是占位文本。现在统一走
 * {@link DocumentPreviewService}（从 MinIO 读真实内容，可选叠加动态水印），
 * 与 {@code GET /documents/{id}/preview} 行为一致。
 */
@Slf4j
@RestController
@RequestMapping("/preview")
@RequiredArgsConstructor
@Tag(name = "preview", description = "文档预览")
public class PreviewController {

    private final DocumentPreviewService previewService;

    /**
     * 文档预览（含动态明水印）。
     *
     * @param format html（默认）→ 浏览器直接内联渲染；json → 返回元信息 + base64
     */
    @GetMapping("/{doc_id}")
    @Operation(summary = "文档预览（含 Canvas 动态明水印）")
    public ResponseEntity<?> preview(
            @PathVariable("doc_id") Long docId,
            @RequestParam(required = false, defaultValue = "html") String format,
            @CurrentUserId(required = false) Long userId) {

        log.info("doc_preview doc_id={} format={} user_id={}", docId, format, userId);
        DocumentPreviewService.PreviewContent content = previewService.load(docId, userId, true);

        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Watermark-Enabled", "true")
                .body(java.util.Map.of(
                    "doc_id", docId,
                    "mime", content.mime(),
                    "filename", content.filename(),
                    "size", content.bytes().length,
                    "watermark", true,
                    "content_base64", java.util.Base64.getEncoder().encodeToString(content.bytes())));
        }

        // html：直接内联返回文档内容（PDF / 图片 / 文本）
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.mime()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                DocumentPreviewService.contentDisposition(content.inline(), content.filename()))
            .header("X-Watermark-Enabled", "true")
            .body(content.bytes());
    }

    /**
     * 下载文档原文。
     *
     * <p>原实现返回 {@code "ENCRYPTED_DOC_<id>"} 字节流并打上
     * {@code X-Encryption: AES-256-GCM} 头 —— 那不是加密文档，只是一句字符串。
     * 现在返回 MinIO 里的真实原文（attachment 下载，不内联）。
     */
    @GetMapping("/{doc_id}/download")
    @Operation(summary = "下载文档原文")
    public ResponseEntity<ByteArrayResource> download(
            @PathVariable("doc_id") Long docId,
            @CurrentUserId Long userId) {

        log.info("doc_download doc_id={} user_id={}", docId, userId);
        DocumentPreviewService.PreviewContent content = previewService.load(docId, userId, false);
        ByteArrayResource resource = new ByteArrayResource(content.bytes());

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                DocumentPreviewService.contentDisposition(false, content.filename()))
            .body(resource);
    }
}
