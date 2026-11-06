package com.example.edam.controller;

import com.example.edam.model.DocResource;
import com.example.edam.model.SysUser;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.service.WatermarkExtractClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WatermarkController} 提取端点的单测。
 *
 * <p><b>这条测试存在的理由</b>：此前该端点返回的是一个**假的**"没查到泄露"
 * （{@code confidence=0.0} + {@code matched_users=[]}），
 * 而且**什么都没扫过**。那比不提供这个端点更危险 ——
 * 运维看到"没有匹配用户"会得出"这个文件是干净的"这个错误结论。
 *
 * <p>所以这里盯的是三态必须分清：
 * 提取到 → 有匹配用户；没提取到 → 有效结论；提取失败 → 503。
 */
class WatermarkExtractControllerTest {

    private WatermarkExtractClient extractClient;
    private DocResourceRepository docResourceRepository;
    private SysUserRepository sysUserRepository;
    private WatermarkController controller;

    @BeforeEach
    void setUp() {
        extractClient = mock(WatermarkExtractClient.class);
        docResourceRepository = mock(DocResourceRepository.class);
        sysUserRepository = mock(SysUserRepository.class);
        controller = new WatermarkController(
            extractClient, docResourceRepository, sysUserRepository);
    }

    @Test
    @DisplayName("提取到水印 → 按水印文本反查出上传者")
    void extractedWatermarkMatchesUploader() {
        // 水印文本格式：{uploader_id}|{doc_id}|{timestamp}
        when(extractClient.extract(any(byte[].class), anyString()))
            .thenReturn(new WatermarkExtractClient.ExtractResult(true, "10|7|2026-08-12T14:00:00Z"));

        DocResource doc = new DocResource();
        doc.setId(7L);
        doc.setTitle("年度薪酬方案.pdf");
        doc.setUploaderId(10L);
        when(docResourceRepository.selectById(7L)).thenReturn(doc);

        SysUser uploader = new SysUser();
        uploader.setId(10L);
        uploader.setEmployeeNo("E000010");
        uploader.setRealName("张三");
        when(sysUserRepository.selectById(10L)).thenReturn(uploader);

        Map<String, Object> resp = controller.extract(file("pdf"), "pdf", 1L).getBody();

        assertNotNull(resp);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> matched =
            (java.util.List<Map<String, Object>>) resp.get("matched_users");
        assertEquals(1, matched.size());
        assertEquals("E000010", matched.get(0).get("employee_no"));
        assertEquals(7L, matched.get(0).get("doc_id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> extracted =
            (Map<String, Object>) resp.get("extracted");
        assertEquals(1.0, extracted.get("confidence"));
        assertEquals("E000010", extracted.get("employee_no"));
    }

    @Test
    @DisplayName("文件里没有水印 → 有效结论（confidence 0，matched_users 空）")
    void noWatermarkIsAValidConclusion() {
        when(extractClient.extract(any(byte[].class), anyString()))
            .thenReturn(new WatermarkExtractClient.ExtractResult(false, ""));

        Map<String, Object> resp = controller.extract(file("pdf"), "pdf", 1L).getBody();

        assertNotNull(resp);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> matched =
            (java.util.List<Map<String, Object>>) resp.get("matched_users");
        assertTrue(matched.isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) resp.get("extracted");
        assertEquals(0.0, extracted.get("confidence"));
    }

    @Test
    @DisplayName("Worker 不可用 → 503，绝不返回\"没查到泄露\"")
    void workerUnavailableIs503NotAnEmptyResult() {
        when(extractClient.extract(any(byte[].class), anyString()))
            .thenThrow(new WatermarkExtractClient.WorkerUnavailableException(
                "未配置 edam.worker.base-url"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.extract(file("pdf"), "pdf", 1L));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }

    @Test
    @DisplayName("空文件 → 400")
    void emptyFileRejected() {
        MockMultipartFile empty = new MockMultipartFile(
            "file", "x.pdf", "application/pdf", new byte[0]);
        assertThrows(IllegalArgumentException.class,
            () -> controller.extract(empty, "pdf", 1L));
    }

    @Test
    @DisplayName("水印文本里的 doc_id 查无此文档 → 不报错，也不编造匹配用户")
    void unknownDocIdYieldsNoMatch() {
        when(extractClient.extract(any(byte[].class), anyString()))
            .thenReturn(new WatermarkExtractClient.ExtractResult(true, "10|999|ts"));
        when(docResourceRepository.selectById(999L)).thenReturn(null);

        Map<String, Object> resp = controller.extract(file("pdf"), "pdf", 1L).getBody();

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> matched =
            (java.util.List<Map<String, Object>>) resp.get("matched_users");
        assertTrue(matched.isEmpty());
    }

    @Test
    @DisplayName("水印文本格式不认识 → 不编造匹配用户")
    void unrecognizedWatermarkTextYieldsNoMatch() {
        when(extractClient.extract(any(byte[].class), anyString()))
            .thenReturn(new WatermarkExtractClient.ExtractResult(true, "garbage"));

        Map<String, Object> resp = controller.extract(file("pdf"), "pdf", 1L).getBody();

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> matched =
            (java.util.List<Map<String, Object>>) resp.get("matched_users");
        assertTrue(matched.isEmpty());
    }

    private MockMultipartFile file(String type) {
        return new MockMultipartFile(
            "file", "sample." + type, "application/" + type,
            "content".getBytes(StandardCharsets.UTF_8));
    }
}
