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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /**
     * 造一个 MinIO 返回的流。
     *
     * <p>{@code GetObjectResponse extends FilterInputStream}，
     * 所以直接用内存流构造，模拟 MinIO 返回 m3u8 内容。
     */
    private io.minio.GetObjectResponse minioStream(String content) throws Exception {
        return new io.minio.GetObjectResponse(
            new okhttp3.Headers.Builder().build(),
            "edam-videos", "us-east-1", "videos/1/hls/playlist.m3u8",
            new java.io.ByteArrayInputStream(content.getBytes("UTF-8")));
    }

    private ErrorResponse errorResponse(String code) {
        // 构造签名：(code, message, bucketName, objectName, resource, requestId, hostId)
        return new ErrorResponse(code, "not found", "edam-videos",
            "videos/1/hls/playlist.m3u8", "videos/1/hls/playlist.m3u8", "req-1", "host-1");
    }

    // ==================================================================
    // m3u8 改写：分片/密钥地址必须是**根相对**路径
    // ==================================================================

    /**
     * m3u8 里的分片与密钥地址必须改写成**根相对**路径。
     *
     * <p>为什么不能用绝对路径：绝对路径要后端先猜出浏览器侧的 origin，
     * 而 nginx 的 {@code X-Forwarded-Host: $host} **不含端口**
     * （含端口的是 {@code $http_host}），
     * 于是 {@code https://host:65173} 被猜成 {@code https://host} ⇒ 端口丢了。
     *
     * <p>后果很隐蔽：manifest 本身是同源相对路径，能正常下载；
     * 但 m3u8 里的分片/密钥指向了另一个 origin ⇒ 浏览器连不上 ⇒
     * hls.js **收不到任何 HTTP 响应**（只能等超时）⇒ 前端只报 "HTTP ??"。
     *
     * <p>根相对路径由 hls.js 用 manifest 自身的 URL 解析 ——
     * manifest 是浏览器按同源拉下来的，origin/端口必然正确。
     */
    @Test
    @DisplayName("m3u8 改写：分片/密钥用根相对路径，不含猜出来的 origin")
    void rewriteUsesRootRelativeUrls() throws Exception {
        String m3u8 = String.join("\n",
            "#EXTM3U",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
            "#EXTINF:10.0,",
            "segment_000.ts",
            "#EXTINF:10.0,",
            "segment_001.ts",
            "") ;
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(minioStream(m3u8));

        ResponseEntity<byte[]> resp = controller.getPlaylist(1L, "tok", request());
        String body = new String(resp.getBody(), "UTF-8");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(body.contains("/api/v1/playback/1/segment/segment_000.ts?token=tok"),
            "分片地址必须是根相对路径，实际输出：\n" + body);
        assertTrue(body.contains("/api/v1/playback/1/segment/segment_001.ts?token=tok"),
            "分片地址必须是根相对路径，实际输出：\n" + body);
        assertTrue(body.contains("URI=\"/api/v1/playback/1/key?token=tok\""),
            "密钥地址必须是根相对路径，实际输出：\n" + body);
        assertFalse(body.contains("http://localhost:8092"),
            "改写后的 m3u8 不应包含后端自己的 origin（那是内网地址），实际输出：\n" + body);
    }

    @Test
    @DisplayName("m3u8 改写：不受 X-Forwarded-Host 丢端口的影响")
    void rewriteIgnoresForwardedHostPortLoss() throws Exception {
        // nginx 的 $host 不含端口 ⇒ 后端拿到的 X-Forwarded-Host 没有 :65173
        String m3u8 = "#EXTM3U\n#EXTINF:10.0,\nsegment_000.ts\n";
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(minioStream(m3u8));

        HttpServletRequest req = request();
        when(req.getHeader("X-Forwarded-Host")).thenReturn("218.4.173.194");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https");

        String body = new String(controller.getPlaylist(1L, "tok", req).getBody(), "UTF-8");

        assertTrue(body.contains("/api/v1/playback/1/segment/segment_000.ts"),
            "必须是根相对路径（不依赖代理头），实际输出：\n" + body);
        assertFalse(body.contains("218.4.173.194"),
            "不应把代理头里的 host 写进 m3u8，实际输出：\n" + body);
    }

    @Test
    @DisplayName("m3u8 改写：保留 #EXTINF 等非分片行不动")
    void rewriteKeepsOtherLines() throws Exception {
        String m3u8 = String.join("\n",
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:10",
            "#EXTINF:10.0,",
            "segment_000.ts",
            "#EXT-X-ENDLIST",
            "");
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(minioStream(m3u8));

        String body = new String(controller.getPlaylist(1L, "tok", request()).getBody(), "UTF-8");

        assertTrue(body.contains("#EXT-X-VERSION:3"));
        assertTrue(body.contains("#EXTINF:10.0,"));
        assertTrue(body.contains("#EXT-X-ENDLIST"));
        assertTrue(body.contains("/api/v1/playback/1/segment/segment_000.ts?token=tok"));
    }
}
