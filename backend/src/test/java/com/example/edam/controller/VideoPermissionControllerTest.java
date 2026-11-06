package com.example.edam.controller;

import com.example.edam.model.VideoResource;
import com.example.edam.security.CurrentUserArgumentResolver;
import com.example.edam.service.VideoPermissionService;
import com.example.edam.service.VideoService;
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
 * 视频授权接口的 MockMvc 测试。
 *
 * <p><b>为什么用 MockMvc 而不是直接调 controller</b>：
 * 要验的是**HTTP 层的契约**，其中最关键的一条：
 * <b>DELETE 带请求体</b>（撤销授权要用 body 传 {@code video_id + user_ids}，
 * 因为 user_ids 是数组放不进 query string）。
 *
 * <p>DELETE 带 body 是合法的 HTTP（RFC 9110 允许 DELETE 有请求体），
 * Spring 的 {@code @RequestBody} 也支持 —— 但这值得用真请求验一遍，
 * 而不是靠"应该可以"。
 */
class VideoPermissionControllerTest {

    private static final Long VIDEO_ID = 7L;
    private static final Long OPERATOR = 10L;

    private VideoService videoService;
    private VideoPermissionService videoPermissionService;
    private MockMvc mockMvc;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        videoService = mock(VideoService.class);
        videoPermissionService = mock(VideoPermissionService.class);
        VideoController controller = new VideoController(
            videoService, videoPermissionService,
            mock(com.example.edam.security.WorkerTokenVerifier.class));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .build();

        // 登录态：当前用户 id
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                String.valueOf(OPERATOR), null,
                AuthorityUtils.createAuthorityList("ROLE_admin")));

        VideoResource video = new VideoResource();
        video.setId(VIDEO_ID);
        video.setUploaderId(OPERATOR);
        video.setClassificationLv(2);
        when(videoService.getById(VIDEO_ID)).thenReturn(video);
        when(videoPermissionService.canDistribute(any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("POST 批量授权 ⇒ 落库并返回 granted_count")
    void grantWritesAndReturnsCount() throws Exception {
        when(videoPermissionService.grant(eq(VIDEO_ID), anyList(), eq(1), any(), eq(OPERATOR)))
            .thenReturn(2);

        mockMvc.perform(post("/videos/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of(
                    "video_id", VIDEO_ID,
                    "user_ids", List.of(20, 30),
                    "actions", 1))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.granted_count").value(2));
    }

    @Test
    @DisplayName("DELETE 撤销授权：请求体里的 video_id + user_ids 要能被解析")
    void revokeParsesRequestBody() throws Exception {
        when(videoPermissionService.revoke(eq(VIDEO_ID), anyList())).thenReturn(1);

        mockMvc.perform(delete("/videos/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of(
                    "video_id", VIDEO_ID,
                    "user_ids", List.of(20)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked_count").value(1));

        // 关键断言：user_ids 真的从 body 里取到了（不是空数组）
        verify(videoPermissionService).revoke(eq(VIDEO_ID), eq(List.of(20L)));
    }

    @Test
    @DisplayName("没有再分享资格 ⇒ 403，且不落库")
    void distributeDeniedReturns403() throws Exception {
        when(videoPermissionService.canDistribute(any(), any())).thenReturn(false);

        mockMvc.perform(post("/videos/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of(
                    "video_id", VIDEO_ID,
                    "user_ids", List.of(20),
                    "actions", 1))))
            .andExpect(status().isForbidden());

        verify(videoPermissionService, never()).grant(any(), anyList(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("actions 缺失 ⇒ 400，不落库")
    void missingActionsReturns400() throws Exception {
        mockMvc.perform(post("/videos/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of(
                    "video_id", VIDEO_ID,
                    "user_ids", List.of(20)))))
            .andExpect(status().isBadRequest());

        verify(videoPermissionService, never()).grant(any(), anyList(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("user_ids 为空 ⇒ 400")
    void emptyUserIdsReturns400() throws Exception {
        mockMvc.perform(post("/videos/batch-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of(
                    "video_id", VIDEO_ID,
                    "user_ids", List.of(),
                    "actions", 1))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET 授权列表")
    void listPermissions() throws Exception {
        when(videoPermissionService.listByVideo(VIDEO_ID)).thenReturn(List.of());

        mockMvc.perform(get("/videos/batch-permission").param("video_id", String.valueOf(VIDEO_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.video_id").value(VIDEO_ID));
    }
}
