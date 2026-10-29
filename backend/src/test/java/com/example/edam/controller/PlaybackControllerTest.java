package com.example.edam.controller;

import com.example.edam.security.JwtTokenProvider;
import com.example.edam.service.AuditService;
import com.example.edam.service.VideoService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PlaybackController} 的 m3u8 端点单测。
 *
 * <p><b>为什么这个用例必须存在</b>：出过一次**用户可见的故障** ——
 * 视频还没转码完就去点播放，前端报
 * "网络异常，正在重试（1/3）… (HTTP 404)"。
 *
 * <p>但那个 404 的**真实原因不是网络**：
 * <ul>
 *   <li>MinIO 上 {@code videos/{id}/hls/playlist.m3u8} 压根不存在（转码没跑）</li>
 *   <li>而 Worker 进程没起来，任务卡在 RabbitMQ 队列里（queue_depth=1）</li>
 * </ul>
 *
 * <p>后端当时把<b>所有</b>异常一律报 404，包括"MinIO 连不上"这种
 * 基础设施故障 —— 运维看日志会被带偏，去查"文件是不是被删了"，而真正该查的是
 * MinIO 连不连得上 / Worker 活不活着。
 *
 * <p>本用例锁住两条不变量：
 * <ol>
 *   <li>对象不存在 ⇒ <b>404</b>（前端据此提示"产物不在对象存储里"）</li>
 *   <li>MinIO 不可达 ⇒ <b>503</b>（前端据此提示"对象存储暂时不可用"）</li>
 * </ol>
 */
class PlaybackControllerTest {

    private PlaybackController controller;
    private MinioClient minioClient;
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        VideoService videoService = mock(VideoService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        minioClient = mock(MinioClient.class);
        AuditService auditService = mock(AuditService.class);
        controller = new PlaybackController(videoService, jwtTokenProvider, minioClient, auditService);
        ReflectionTestUtils.setField(controller, "videosBucket", "edam-videos");
        ReflectionTestUtils.setField(controller, "keyUrlTemplate", "/api/v1/playback/%d/key");
    }

    @Test
    @DisplayName("MinIO 上没有该对象 ⇒ 404（产物不存在）")
    void missingObjectReturns404() throws Exception {
        when(minioClient.getObject(any(GetObjectArgs.class)))
            .thenThrow(new ErrorResponseException(
                errorResponse("NoSuchKey"), mock(Response.class), "not found"));

        ResponseEntity<byte[]> resp = controller.getPlaylist(1L, "tok", request());

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    @DisplayName("MinIO 连不上 ⇒ 503（基础设施故障，不是文件不存在）")
    void minioUnreachableReturns503() throws Exception {
        when(minioClient.getObject(any(GetObjectArgs.class)))
            .thenThrow(new IOException("Connection refused"));

        ResponseEntity<byte[]> resp = controller.getPlaylist(1L, "tok", request());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode(),
            "MinIO 不可达必须报 503 —— 报 404 会让运维误以为文件被删了");
    }

    @Test
    @DisplayName("token 无效 ⇒ 401（不碰 MinIO）")
    void invalidTokenReturns401() throws Exception {
        when(jwtTokenProvider.parseAndValidate(any())).thenThrow(new IllegalArgumentException("bad token"));

        ResponseEntity<byte[]> resp = controller.getPlaylist(1L, "bad", request());

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    private HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8092/api/v1/playback/1/playlist.m3u8"));
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        return req;
    }

    private ErrorResponse errorResponse(String code) {
        // 构造签名：(code, message, bucketName, objectName, resource, requestId, hostId)
        return new ErrorResponse(code, "not found", "edam-videos",
            "videos/1/hls/playlist.m3u8", "videos/1/hls/playlist.m3u8", "req-1", "host-1");
    }
}
