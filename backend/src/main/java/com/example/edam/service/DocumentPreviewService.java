package com.example.edam.service;

import com.example.edam.model.DocResource;
import com.example.edam.repository.SysUserRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文档预览 / 下载的字节流生成。
 *
 * <p><b>为什么抽出来</b>：真正的预览实现（从 MinIO 读 → 加水印 → 输出）
 * 原本只存在于 {@code DocumentController.preview} 里；而
 * {@code PreviewController} 是另两个**占位端点**，返回写死的 HTML 和
 * {@code "ENCRYPTED_DOC_<id>"} 字符串 —— 前端以为拿到了文档预览/加密文件，
 * 实际拿到的是占位文本。
 *
 * <p>现在两个 controller 共用本 service，行为一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPreviewService {

    private final MinioClient minioClient;
    private final WatermarkService watermarkService;
    private final SysUserRepository sysUserRepository;
    private final DocumentService documentService;

    @Value("${minio.bucket.documents}")
    private String documentsBucket;

    /**
     * 预览结果：字节 + MIME + 文件名 + 是否可内联展示。
     */
    public record PreviewContent(byte[] bytes, String mime, String filename, boolean inline) {
    }

    /**
     * 读取文档原文（可选加水印）。
     *
     * @param docId        文档 ID
     * @param userId       当前用户（用于水印文本；null = 不加水印）
     * @param withWatermark 是否叠加动态水印
     */
    public PreviewContent load(Long docId, Long userId, boolean withWatermark) {
        DocResource doc = documentService.getById(docId);
        if (doc.getMinioPath() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档原文不存在");
        }

        String mime = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
        boolean inline = mime.startsWith("application/pdf") || mime.startsWith("image/")
                || mime.startsWith("text/");
        String filename = doc.getTitle() != null ? doc.getTitle() : ("doc_" + docId);

        byte[] bytes;
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(documentsBucket)
                    .object(doc.getMinioPath())
                    .build())) {
            if (withWatermark) {
                String watermarkText = lookupEmployeeNo(userId);
                if (watermarkText == null || watermarkText.isBlank()) {
                    watermarkText = "ANONYMOUS";
                }
                byte[] watermarked = watermarkService.applyWatermark(in, mime, watermarkText);
                bytes = (watermarked != null && watermarked.length > 0)
                    ? watermarked
                    : in.readAllBytes();
            } else {
                bytes = in.readAllBytes();
            }
        } catch (Exception e) {
            log.error("doc_content_load_failed, doc_id={}", docId, e);
            throw new RuntimeException("文档读取失败: " + docId, e);
        }

        log.info("doc_content_loaded, doc_id={}, user_id={}, mime={}, size={}, watermark={}",
            docId, userId, mime, bytes.length, withWatermark);
        return new PreviewContent(bytes, mime, filename, inline);
    }

    /**
     * 取员工号（用于水印文本）；查不到就降级。
     */
    public String lookupEmployeeNo(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            com.example.edam.model.SysUser u = sysUserRepository.selectById(userId);
            return u != null ? u.getEmployeeNo() : null;
        } catch (Exception e) {
            log.warn("employee_no_lookup_failed, userId={}", userId);
            return null;
        }
    }

    /**
     * 拼 Content-Disposition（RFC 5987 编码，兼容中文文件名）。
     */
    public static String contentDisposition(boolean inline, String filename) {
        String encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8)
            .replace("+", "%20");
        return (inline ? "inline" : "attachment")
            + "; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
    }

    /**
     * 通用安全响应头（防 iframe 劫持 + 禁止 MIME 嗅探 + 私有缓存）。
     */
    public static void applySecurityHeaders(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
    }
}
