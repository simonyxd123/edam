package com.example.edam.controller;

import com.example.edam.security.JwtTokenProvider;
import com.example.edam.service.AuditService;
import com.example.edam.service.VideoPermissionService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        VideoPermissionService videoPermissionService = mock(VideoPermissionService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        minioClient = mock(MinioClient.class);
        AuditService auditService = mock(AuditService.class);
        controller = new PlaybackController(videoService, videoPermissionService,
            jwtTokenProvider, minioClient, auditService);
        ReflectionTestUtils.setField(controller, "videosBucket", "edam-videos");
        ReflectionTestUtils.setField(controller, "keyUrlTemplate", "/api/v1/playback/%d/key");
        // 默认：token 验签通过、且是绑定到该视频的播放 token
        when(jwtTokenProvider.parseAndValidate(any())).thenReturn(mock(io.jsonwebtoken.Claims.class));
        when(jwtTokenProvider.isPlaybackTokenFor(any(), any())).thenReturn(true);
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

    @Test
    @DisplayName("token 不是本视频的播放 token ⇒ 401（普通登录 token 不能拉播放流）")
    void tokenNotBoundToThisVideoReturns401() throws Exception {
        // 验签通过，但 scope 不是 playback / video_id 不匹配 ⇒ 拒绝
        when(jwtTokenProvider.parseAndValidate(any())).thenReturn(mock(io.jsonwebtoken.Claims.class));
        when(jwtTokenProvider.isPlaybackTokenFor(any(), any())).thenReturn(false);

        ResponseEntity<byte[]> resp = controller.getPlaylist(1L, "tok", request());

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verify(minioClient, never()).getObject(any(GetObjectArgs.class));

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

    // ==================================================================
    // m3u8 改写：EXT-X-KEY 的 URI —— **任何形态**都要换成后端 key 端点
    //
    // 2026-11-03 线上故障：老实现只改写 URI="key.bin"，
    // 于是存量播放列表里的其它 URI 形态原样发出去，
    // hls.js 拿到的 key 地址要么指向错的地方，要么是**空的** ——
    // 空的那条最隐蔽：hls.js 压根不发请求，只抛 keyLoadError，
    // 前端显示成"无响应（超时或连接被拒）"，把地址问题说成了网络问题。
    // ==================================================================

    /**
     * 各种 EXT-X-KEY 形态都要被换成后端 key 端点。
     *
     * <p>覆盖存量 MinIO 里真实出现过的四种形态：
     * 十六进制 URI（key.info 假成功）、绝对地址、空 URI、缺 URI 属性。
     */
    @Test
    @DisplayName("m3u8 改写：EXT-X-KEY 的 URI 无论原值是什么都换成后端 key 端点")
    void rewriteKeyUriWhateverItIs() throws Exception {
        String[] keyLines = {
            // 正常形态
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
            // 2026-10-29/30 key.info 假成功：FFmpeg 把 key 的十六进制当 URI
            "#EXT-X-KEY:METHOD=AES-128,URI=\"42ccf808b90e06e674095a71f25f1106\"",
            // 更早的 Worker 把绝对地址烤进了 m3u8
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://api.example.com/api/v1/playback/1/key\"",
            // URI 为空 —— hls.js 会抛 Invalid key URI，连请求都不发
            "#EXT-X-KEY:METHOD=AES-128,URI=\"\"",
            // 压根没有 URI 属性
            "#EXT-X-KEY:METHOD=AES-128",
        };

        for (String keyLine : keyLines) {
            String m3u8 = String.join("\n",
                "#EXTM3U",
                keyLine,
                "#EXTINF:10.0,",
                "segment_000.ts",
                "");
            when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(minioStream(m3u8));

            String body = new String(controller.getPlaylist(1L, "tok", request()).getBody(), "UTF-8");

            assertTrue(body.contains("URI=\"/api/v1/playback/1/key?token=tok\""),
                "密钥地址必须换成后端 key 端点（原 EXT-X-KEY 行：" + keyLine + "），实际输出：\n" + body);
            assertFalse(body.contains("42ccf808b90e06e674095a71f25f1106"),
                "十六进制 URI 不该残留，实际输出：\n" + body);
            assertFalse(body.contains("api.example.com"),
                "绝对地址不该残留，实际输出：\n" + body);
        }
    }

    @Test
    @DisplayName("m3u8 改写：EXT-X-KEY 的其它属性（IV 等）要保留")
    void rewriteKeyKeepsOtherAttributes() throws Exception {
        String m3u8 = String.join("\n",
            "#EXTM3U",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x0123456789abcdef0123456789abcdef",
            "#EXTINF:10.0,",
            "segment_000.ts",
            "");
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(minioStream(m3u8));

        String body = new String(controller.getPlaylist(1L, "tok", request()).getBody(), "UTF-8");

        assertTrue(body.contains("METHOD=AES-128"), "METHOD 必须保留，实际输出：\n" + body);
        assertTrue(body.contains("IV=0x0123456789abcdef0123456789abcdef"),
            "IV 必须保留，实际输出：\n" + body);
        assertTrue(body.contains("URI=\"/api/v1/playback/1/key?token=tok\""),
            "密钥地址必须换成后端 key 端点，实际输出：\n" + body);
    }

    @Test
    @DisplayName("m3u8 改写：METHOD=NONE 的行不许动（那一段本来就不加密）")
    void rewriteLeavesMethodNoneAlone() throws Exception {
        String m3u8 = String.join("\n",
            "#EXTM3U",
            "#EXT-X-KEY:METHOD=NONE",
            "#EXTINF:10.0,",
            "segment_000.ts",
            "");
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(minioStream(m3u8));

        String body = new String(controller.getPlaylist(1L, "tok", request()).getBody(), "UTF-8");

        assertTrue(body.contains("#EXT-X-KEY:METHOD=NONE"),
            "METHOD=NONE 不该被改写，实际输出：\n" + body);
        assertFalse(body.contains("URI="), "METHOD=NONE 不该被塞进 URI，实际输出：\n" + body);
    }

    @Test
    @DisplayName("m3u8 改写：多行 EXT-X-KEY 都要改（每行一个 key）")
    void rewriteAllKeyLines() throws Exception {
        String m3u8 = String.join("\n",
            "#EXTM3U",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
            "#EXTINF:10.0,",
            "segment_000.ts",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
            "#EXTINF:10.0,",
            "segment_001.ts",
            "");
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(minioStream(m3u8));

        String body = new String(controller.getPlaylist(1L, "tok", request()).getBody(), "UTF-8");

        int occurrences = body.split("URI=\"/api/v1/playback/1/key\\?token=tok\"", -1).length - 1;
        assertEquals(2, occurrences, "两行 EXT-X-KEY 都要改写，实际输出：\n" + body);
    }
}
