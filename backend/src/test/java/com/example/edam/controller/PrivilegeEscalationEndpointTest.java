package com.example.edam.controller;

import com.example.edam.exception.GlobalExceptionHandler;
import com.example.edam.model.DocPermission;
import com.example.edam.model.DocResource;
import com.example.edam.model.SysUser;
import com.example.edam.repository.DocPermissionRepository;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * **越权转授**的端点级回归测试（2026-11-11 代码审计第三轮 §1，critical）。
 *
 * <p><b>为什么不用 mock 的 service</b>：
 * {@link DocPermissionControllerTest} 把 {@code DocPermissionService} mock 掉了，
 * 所以它<b>测不到这个漏洞</b> —— mock 不会执行真正的校验逻辑。
 *
 * <p>本测试接的是**真实的 {@link DocPermissionService}**
 * （只把 repository mock 掉），走完整的
 * 「HTTP 请求 → Controller → Service 校验 → 写库」链路。
 * 这才能证明：
 * <ol>
 *   <li>越权转授真的被挡住了（403，且**没有写库**）</li>
 *   <li>403 的响应体带 {@code detail} —— 前端要靠它告诉用户
 *       "不能授出自己没有的权限：下载、打印"</li>
 * </ol>
 */
class PrivilegeEscalationEndpointTest {

    private static final Long DOC_ID = 42L;
    private static final Long UPLOADER = 10L;   // A：上传者
    private static final Long MIDDLE = 20L;     // B：被授 view|distribute
    private static final Long TARGET = 30L;     // C：被越权授权的目标

    private DocPermissionRepository docPermissionRepository;
    private MockMvc mockMvc;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        docPermissionRepository = mock(DocPermissionRepository.class);
        DocResourceRepository docResourceRepository = mock(DocResourceRepository.class);
        SysUserRepository sysUserRepository = mock(SysUserRepository.class);

        // **真实的 service** —— 校验逻辑必须真的跑起来
        DocPermissionService docPermissionService = new DocPermissionService(
            docPermissionRepository, docResourceRepository, sysUserRepository);

        DocumentService documentService = mock(DocumentService.class);
        DocResource doc = new DocResource();
        doc.setId(DOC_ID);
        doc.setUploaderId(UPLOADER);
        doc.setClassificationLv(3);   // L3：排除密级兜底
        when(documentService.getById(DOC_ID)).thenReturn(doc);

        DocumentController controller = new DocumentController(
            documentService,
            mock(com.example.edam.repository.DocResourceRepository.class),
            mock(io.minio.MinioClient.class),
            mock(com.example.edam.service.WatermarkService.class),
            mock(com.example.edam.service.DocumentPreviewService.class),
            docPermissionService,
            mock(com.example.edam.repository.SysUserRepository.class),
            mock(com.example.edam.security.WorkerTokenVerifier.class),
            mock(com.example.edam.service.WatermarkRegistryService.class));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        // 当前登录的是 B（不是上传者、不是 admin）
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                String.valueOf(MIDDLE), null,
                AuthorityUtils.createAuthorityList("document:share")));

        // B 持有 view|distribute（17）—— A 给的"允许转发"
        grantRow(MIDDLE,
            DocPermissionService.ACTION_VIEW | DocPermissionService.ACTION_DISTRIBUTE, null);

        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc);
        when(sysUserRepository.findById(anyLong())).thenReturn(user(anyLong()));
    }

    @Test
    @DisplayName("持 distribute 位的人授出 ACTION_ALL ⇒ 403，且没写库")
    void escalationIsBlockedAndNothingIsWritten() throws Exception {
        mockMvc.perform(post("/documents/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "user_ids", List.of(TARGET),
                    "actions", DocPermissionService.ACTION_ALL))))
            .andExpect(status().isForbidden())
            // 前端靠 detail 告诉用户到底哪里越界了
            .andExpect(jsonPath("$.detail").exists())
            .andExpect(jsonPath("$.detail").value(
                org.hamcrest.Matchers.containsString("下载")));

        // **绝不能**写库
        verify(docPermissionRepository, never()).insert(any(DocPermission.class));
        verify(docPermissionRepository, never()).updateById(any(DocPermission.class));
    }

    @Test
    @DisplayName("授出自己持有的位 ⇒ 200（正常转发不被误伤）")
    void reSharingOwnActionsStillWorks() throws Exception {
        mockMvc.perform(post("/documents/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "user_ids", List.of(TARGET),
                    "actions", DocPermissionService.ACTION_VIEW
                        | DocPermissionService.ACTION_DISTRIBUTE))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.granted_count").value(1));

        verify(docPermissionRepository).insert(any(DocPermission.class));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void grantRow(Long userId, int actions, OffsetDateTime expireAt) {
        DocPermission row = new DocPermission();
        row.setId(1L);
        row.setDocId(DOC_ID);
        row.setUserId(userId);
        row.setActions(actions);
        row.setExpireAt(expireAt);
        row.setGrantedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(docPermissionRepository.findByDocAndUser(eq(DOC_ID), eq(userId)))
            .thenReturn(List.of(row));
    }

    private static SysUser user(Long id) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setDeptId(200L);
        return u;
    }
}
