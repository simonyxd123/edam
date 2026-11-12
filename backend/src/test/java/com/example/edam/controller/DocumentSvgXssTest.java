package com.example.edam.controller;

import com.example.edam.model.DocResource;
import com.example.edam.security.CurrentUserArgumentResolver;
import com.example.edam.service.DocPermissionService;
import com.example.edam.service.DocumentPreviewService;
import com.example.edam.service.DocumentService;
import com.example.edam.service.WatermarkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SVG 存储型 XSS 的回归测试
 * （2026-11-11 代码审计第四轮 §3，定级 high）。
 *
 * <p><b>攻击路径</b>：
 * <ol>
 *   <li>攻击者（有 {@code document:upload}）上传 {@code payload.svg}，
 *       {@code Content-Type: image/svg+xml}，内容含 {@code <script>}</li>
 *   <li>把文档分享给受害者（{@code view} 位即可）</li>
 *   <li>受害者打开预览 ⇒ 脚本在<b>后端域名</b>下执行</li>
 *   <li>受害者浏览器里有 {@code access_token} / {@code refresh_token}
 *       （{@code stores/user.ts} 存在 localStorage）⇒ <b>JWT 被直接偷走</b></li>
 * </ol>
 *
 * <p><b>为什么 {@code nosniff} 挡不住</b>：它只防"声明类型与实际内容不符"，
 * 而 SVG 的声明类型（{@code image/svg+xml}）<b>就是</b>浏览器会执行脚本的类型。
 *
 * <p><b>三个修复点</b>（缺一不可）：
 * <ol>
 *   <li>上传白名单排除 SVG —— {@code DocumentController.isAllowedDocumentType}</li>
 *   <li>落库 MIME 由服务端判定，不信客户端的 Content-Type
 *       —— {@code DocumentService.resolveMimeType}</li>
 *   <li>预览 inline 白名单改成<b>精确枚举</b>
 *       —— {@code DocumentPreviewService.isInlineSafe}</li>
 * </ol>
 */
class DocumentSvgXssTest {

    private DocumentService documentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        DocPermissionService docPermissionService = mock(DocPermissionService.class);
        DocumentController controller = new DocumentController(
            documentService,
            mock(com.example.edam.repository.DocResourceRepository.class),
            mock(io.minio.MinioClient.class),
            mock(WatermarkService.class),
            mock(DocumentPreviewService.class),
            docPermissionService,
            mock(com.example.edam.repository.SysUserRepository.class),
            mock(com.example.edam.security.WorkerTokenVerifier.class));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .build();

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "10", null, AuthorityUtils.createAuthorityList("document:upload")));
    }

    // ==================================================================
    // 1. 上传白名单：SVG 必须被拒
    // ==================================================================

    @Test
    @DisplayName("上传 SVG（image/svg+xml）⇒ 400")
    void svgUploadIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "payload.svg", "image/svg+xml",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>fetch('/x')</script></svg>"
                .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/documents")
                .file(file)
                .param("classification_lv", "L1"))
            .andExpect(status().isBadRequest());

        // 关键：**绝不能**落到 Service（否则文件会真的进库）
        verify(documentService, never()).upload(any(), anyString(), any(),
            anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("上传 SVG 但后缀不是 .svg（.html）⇒ 也被拒（text/html 是最经典的 XSS 载体）")
    void htmlUploadIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "payload.html", "text/html",
            "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/documents")
                .file(file)
                .param("classification_lv", "L1"))
            .andExpect(status().isBadRequest());

        verify(documentService, never()).upload(any(), anyString(), any(),
            anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("上传 .svg 后缀但 MIME 声称是 image/png ⇒ 也被拒")
    void svgExtensionWithFakeMimeIsRejected() throws Exception {
        // 后缀分支也必须挡住 —— 不能只靠 MIME 分支
        MockMultipartFile file = new MockMultipartFile(
            "file", "payload.svg", "image/png",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x</script></svg>"
                .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/documents")
                .file(file)
                .param("classification_lv", "L1"))
            .andExpect(status().isBadRequest());

        verify(documentService, never()).upload(any(), anyString(), any(),
            anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("正常图片仍然放行（别把功能改坏了）")
    void normalImageStillAccepted() throws Exception {
        DocResource doc = new DocResource();
        doc.setId(1L);
        doc.setFileHash("hash");
        doc.setClassificationLv(1);
        when(documentService.upload(any(), anyString(), any(), anyBoolean(), anyLong()))
            .thenReturn(doc);

        MockMultipartFile file = new MockMultipartFile(
            "file", "photo.png", "image/png",
            new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        mockMvc.perform(multipart("/documents")
                .file(file)
                .param("classification_lv", "L1"))
            .andExpect(status().isAccepted());

        verify(documentService).upload(any(), anyString(), any(), anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("PDF 仍然放行")
    void pdfStillAccepted() throws Exception {
        DocResource doc = new DocResource();
        doc.setId(1L);
        doc.setFileHash("hash");
        doc.setClassificationLv(1);
        when(documentService.upload(any(), anyString(), any(), anyBoolean(), anyLong()))
            .thenReturn(doc);

        MockMultipartFile file = new MockMultipartFile(
            "file", "report.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/documents")
                .file(file)
                .param("classification_lv", "L1"))
            .andExpect(status().isAccepted());
    }

    // ==================================================================
    // 2. 预览 inline 判定：精确枚举
    // ==================================================================

    @Test
    @DisplayName("预览 inline 判定：SVG / HTML 一律不 inline")
    void svgAndHtmlAreNotInline() {
        assertFalse(DocumentPreviewService.isInlineSafe("image/svg+xml"),
            "SVG 绝不能 inline —— 浏览器会执行它的脚本");
        assertFalse(DocumentPreviewService.isInlineSafe("text/html"),
            "text/html 绝不能 inline —— 最经典的 XSS 载体");
        assertFalse(DocumentPreviewService.isInlineSafe("text/plain"),
            "text/* 一律不 inline —— 前缀匹配会把 text/html 带进来");
        assertFalse(DocumentPreviewService.isInlineSafe("application/octet-stream"));
        assertFalse(DocumentPreviewService.isInlineSafe(null));
    }

    @Test
    @DisplayName("预览 inline 判定：PDF 与常见位图仍然 inline")
    void safeTypesStillInline() {
        assertTrue(DocumentPreviewService.isInlineSafe("application/pdf"));
        assertTrue(DocumentPreviewService.isInlineSafe("image/png"));
        assertTrue(DocumentPreviewService.isInlineSafe("image/jpeg"));
        assertTrue(DocumentPreviewService.isInlineSafe("image/gif"));
        assertTrue(DocumentPreviewService.isInlineSafe("image/webp"));
        // 带 charset 参数也要认
        assertTrue(DocumentPreviewService.isInlineSafe("application/pdf; charset=utf-8"));
    }

    // ==================================================================
    // 4. Content-Disposition：SVG 必须走 attachment
    // ==================================================================

    @Test
    @DisplayName("Content-Disposition：非白名单类型一律 attachment")
    void nonInlineTypesGetAttachmentDisposition() {
        assertTrue(DocumentPreviewService.contentDisposition(false, "a.svg")
                .startsWith("attachment"),
            "SVG 必须是 attachment —— inline 会让浏览器渲染并执行它的脚本");
        assertTrue(DocumentPreviewService.contentDisposition(false, "a.html")
                .startsWith("attachment"));
        // 白名单里的仍然是 inline
        assertTrue(DocumentPreviewService.contentDisposition(true, "a.pdf")
                .startsWith("inline"));
    }

    // ==================================================================
    // 3. 落库 MIME 由服务端判定
    // ==================================================================

    @Test
    @DisplayName("落库 MIME 按后缀判定，不信客户端的 Content-Type")
    void storedMimeIsDerivedFromExtension() {
        assertEquals("application/pdf", DocumentService.resolveMimeType("a.pdf"));
        assertEquals("image/png", DocumentService.resolveMimeType("a.png"));
        assertEquals("image/jpeg", DocumentService.resolveMimeType("a.JPG"));
        // SVG ⇒ 二进制流，浏览器不解析
        assertEquals("application/octet-stream", DocumentService.resolveMimeType("a.svg"));
        assertEquals("application/octet-stream", DocumentService.resolveMimeType("a.html"));
        assertEquals("application/octet-stream", DocumentService.resolveMimeType(null));
    }
}
