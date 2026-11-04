package com.example.edam.controller;

import com.example.edam.model.DocResource;
import com.example.edam.security.CurrentUserArgumentResolver;
import com.example.edam.service.DocPermissionService;
import com.example.edam.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档授权接口的 MockMvc 测试（与视频侧同构，另加 print 位）。
 */
class DocPermissionControllerTest {

    private static final Long DOC_ID = 3L;
    private static final Long OPERATOR = 10L;

    private DocPermissionService docPermissionService;
    private MockMvc mockMvc;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        DocumentService documentService = mock(DocumentService.class);
        docPermissionService = mock(DocPermissionService.class);
        DocumentController controller = new DocumentController(
            documentService,
            mock(com.example.edam.repository.DocResourceRepository.class),
            mock(io.minio.MinioClient.class),
            mock(com.example.edam.service.WatermarkService.class),
            mock(com.example.edam.service.DocumentPreviewService.class),
            docPermissionService,
            mock(com.example.edam.repository.SysUserRepository.class));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .build();

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                String.valueOf(OPERATOR), null,
                AuthorityUtils.createAuthorityList("ROLE_admin")));

        DocResource doc = new DocResource();
        doc.setId(DOC_ID);
        doc.setUploaderId(OPERATOR);
        doc.setClassificationLv(2);
        when(documentService.getById(DOC_ID)).thenReturn(doc);
        when(docPermissionService.canDistribute(any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("POST 批量授权 ⇒ 落库并返回 granted_count")
    void grantWritesAndReturnsCount() throws Exception {
        when(docPermissionService.grant(eq(DOC_ID), anyList(), eq(3), any(), eq(OPERATOR)))
            .thenReturn(1);

        mockMvc.perform(post("/documents/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "user_ids", List.of(20),
                    "actions", 3))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.granted_count").value(1));
    }

    @Test
    @DisplayName("DELETE 撤销授权：请求体要能被解析")
    void revokeParsesRequestBody() throws Exception {
        when(docPermissionService.revoke(eq(DOC_ID), anyList())).thenReturn(1);

        mockMvc.perform(delete("/documents/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "user_ids", List.of(20)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked_count").value(1));

        verify(docPermissionService).revoke(eq(DOC_ID), eq(List.of(20L)));
    }

    @Test
    @DisplayName("没有再分享资格 ⇒ 403，且不落库")
    void distributeDeniedReturns403() throws Exception {
        when(docPermissionService.canDistribute(any(), any())).thenReturn(false);

        mockMvc.perform(post("/documents/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "user_ids", List.of(20),
                    "actions", 1))))
            .andExpect(status().isForbidden());

        verify(docPermissionService, never()).grant(any(), anyList(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("GET 授权列表")
    void listPermissions() throws Exception {
        when(docPermissionService.listByDoc(DOC_ID)).thenReturn(List.of());

        mockMvc.perform(get("/documents/batch-permission").param("doc_id", String.valueOf(DOC_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.doc_id").value(DOC_ID));
    }
}
