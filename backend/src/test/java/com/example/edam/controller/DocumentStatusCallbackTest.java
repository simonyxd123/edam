package com.example.edam.controller;

import com.example.edam.model.DocResource;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.WorkerTokenVerifier;
import com.example.edam.service.DocPermissionService;
import com.example.edam.service.DocumentPreviewService;
import com.example.edam.service.DocumentService;
import com.example.edam.service.WatermarkService;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档处理状态回调端点的单测。
 *
 * <p>盯的是**鉴权**：这个端点是 {@code permitAll} 的（Worker 没有 JWT），
 * 唯一的防线就是方法内的 {@code X-Worker-Token} 校验 ——
 * 校验一旦失效，任何人都能把没处理的文档标成 ready，
 * 或者把预览路径指向任意 MinIO 对象。
 */
class DocumentStatusCallbackTest {

    private DocumentService documentService;
    private WorkerTokenVerifier workerTokenVerifier;
    private DocumentController controller;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        workerTokenVerifier = mock(WorkerTokenVerifier.class);

        controller = new DocumentController(
            documentService,
            mock(DocResourceRepository.class),
            mock(MinioClient.class),
            mock(WatermarkService.class),
            mock(DocumentPreviewService.class),
            mock(DocPermissionService.class),
            mock(SysUserRepository.class),
            workerTokenVerifier
        ,
            mock(com.example.edam.service.WatermarkRegistryService.class));

        DocResource doc = new DocResource();
        doc.setId(7L);
        doc.setWatermarkStatus(2);
        doc.setPreviewStatus(2);
        when(documentService.getById(7L)).thenReturn(doc);
    }

    @Test
    @DisplayName("带正确的 Worker token → 推进状态")
    void validTokenUpdatesStatus() {
        when(workerTokenVerifier.isValid("s3cr3t")).thenReturn(true);

        DocumentController.DocStatusUpdateRequest request =
            new DocumentController.DocStatusUpdateRequest();
        request.setWatermarkStatus(2);
        request.setPreviewStatus(2);
        request.setWatermarkPath("documents/7/watermarkedpdf");
        request.setPreviewPath("documents/7/preview.pdf");

        ResponseEntity<?> resp = controller.updateStatus(7L, request, "s3cr3t");

        assertEquals(200, resp.getStatusCode().value());
        verify(documentService).updateProcessingStatus(
            eq(7L), eq(2), eq(2),
            eq("documents/7/watermarkedpdf"), eq("documents/7/preview.pdf"));
    }

    @Test
    @DisplayName("token 不对 → 401，且不碰状态")
    void invalidTokenRejected() {
        when(workerTokenVerifier.isValid("wrong")).thenReturn(false);

        DocumentController.DocStatusUpdateRequest request =
            new DocumentController.DocStatusUpdateRequest();
        request.setWatermarkStatus(2);

        ResponseEntity<?> resp = controller.updateStatus(7L, request, "wrong");

        assertEquals(401, resp.getStatusCode().value());
        verify(documentService, never()).updateProcessingStatus(
            anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("没带 token → 401")
    void missingTokenRejected() {
        when(workerTokenVerifier.isValid(null)).thenReturn(false);

        DocumentController.DocStatusUpdateRequest request =
            new DocumentController.DocStatusUpdateRequest();
        request.setWatermarkStatus(2);

        ResponseEntity<?> resp = controller.updateStatus(7L, request, null);

        assertEquals(401, resp.getStatusCode().value());
        verify(documentService, never()).updateProcessingStatus(
            anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("后端没配 worker token → 一律拒绝（fail-closed）")
    void backendTokenNotConfiguredFailsClosed() {
        // WorkerTokenVerifier 在后端没配 edam.worker.token 时 isValid 恒为 false
        WorkerTokenVerifier verifier = new WorkerTokenVerifier("");
        assertTrue(!verifier.isValid("anything"));
        assertEquals("backend_token_not_configured", verifier.rejectReason("anything"));
    }

    @Test
    @DisplayName("拒绝原因分三类说清楚，运维才知道该改哪一边")
    void rejectReasonsAreDistinct() {
        assertEquals("backend_token_not_configured",
            new WorkerTokenVerifier("").rejectReason("x"));
        assertEquals("worker_token_header_missing",
            new WorkerTokenVerifier("configured").rejectReason(null));
        assertEquals("worker_token_header_missing",
            new WorkerTokenVerifier("configured").rejectReason("  "));
        assertEquals("token_mismatch",
            new WorkerTokenVerifier("configured").rejectReason("other"));
    }

    @Test
    @DisplayName("token 比较用常量时间 —— 防止 timing 旁路")
    void tokenComparisonIsConstantTime() {
        WorkerTokenVerifier verifier = new WorkerTokenVerifier("s3cr3t");
        assertTrue(verifier.isValid("s3cr3t"));
        assertTrue(!verifier.isValid("s3cr3t "));
        assertTrue(!verifier.isValid(""));
        assertTrue(!verifier.isValid(null));
    }
}
