package com.example.edam.controller;

import com.example.edam.model.VideoResource;
import com.example.edam.security.JwtTokenProvider;
import com.example.edam.service.VideoService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 视频播放与鉴权 Controller
 * 对应 openapi.yaml tag: playback
 */
@Slf4j
@RestController
@RequestMapping("/playback")
@RequiredArgsConstructor
public class PlaybackController {

    private final VideoService videoService;
    private final JwtTokenProvider jwtTokenProvider;
    private final MinioClient minioClient;

    @Value("${minio.bucket.videos}")
    private String videosBucket;

    @Value("${edam.hls.key-url-template}")
    private String keyUrlTemplate;

    /**
     * 获取播放 Token
     */
    @PostMapping("/{video_id}/token")
    public ResponseEntity<Map<String, Object>> getToken(
        @PathVariable("video_id") Long videoId,
        @RequestHeader("X-User-Id") Long userId
    ) {
        VideoResource video = videoService.getById(videoId);

        // 1. 签发短时 JWT
        String sessionId = UUID.randomUUID().toString();
        String token = jwtTokenProvider.createAccessToken(userId, sessionId, List.of("ROLE_VIEWER"));

        // 2. 生成签名 URL
        long expires = Instant.now().getEpochSecond() + 600;  // 10 分钟
        String m3u8Url = String.format("/api/v1/playback/%d/playlist.m3u8?token=%s",
            videoId, token);
        // segment URL 模板（给前端展示用，实际由 m3u8 内容决定）
        String keyUrl = String.format(keyUrlTemplate, videoId);

        Map<String, Object> response = new HashMap<>();
        response.put("session_id", sessionId);
        response.put("m3u8_url", m3u8Url);
        response.put("token", token);
        response.put("expires_at", Instant.ofEpochSecond(expires).toString());
        response.put("key_url", String.format(keyUrlTemplate, videoId));
        response.put("watermark_template", "{employee_no}|{timestamp}");

        return ResponseEntity.ok(response);
    }

    /**
     * 获取 HLS playlist.m3u8（流式输出 MinIO 中的 m3u8，并把 segment 路径改写为后端代理路径）
     *
     * Hls.js 不能直接拉 MinIO 的 HLS（缺少 X-User-Id / Authorization），
     * 所以后端代理出 m3u8 + segments，URL 里带 token。
     *
     * 鉴权：dev 阶段 permitAll + 方法内校验 JWT（防止 Hls.js 自动请求时不带 Authorization）。
     * 生产：建议换 presigned MinIO URL（5 分钟过期），无需后端代理。
     */
    @GetMapping(value = "/{video_id}/playlist.m3u8", produces = "application/vnd.apple.mpegurl")
    public ResponseEntity<byte[]> getPlaylist(
            @PathVariable("video_id") Long videoId,
            @RequestParam("token") String token,
            jakarta.servlet.http.HttpServletRequest request) {
        // 1. 验证 token（不依赖 Spring Security context，query string 取）
        try {
            jwtTokenProvider.parseAndValidate(token);
        } catch (Exception e) {
            log.warn("m3u8_token_invalid, video_id={}, error={}", videoId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String objectName = String.format("videos/%d/hls/playlist.m3u8", videoId);
            InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(videosBucket).object(objectName).build());

            byte[] content = stream.readAllBytes();
            stream.close();

            // 推断 segment URL 的 base（从 request URL 拿 scheme + host + port）
            // 反向代理 / 内网穿透场景下，request.getRequestURL() 是后端实际接收的
            // (比如 http://localhost:8092/api/v1/.../playlist.m3u8)
            // 但浏览器实际访问的是 https://218.4.173.194:65173/...
            // 所以用 X-Forwarded-* 或 Origin / Referer 来推断浏览器侧 base
            String browserBase = inferBrowserBase(request);
            log.info("m3u8_rewrite_origin, video_id={}, browser_base={}, request_url={}",
                videoId, browserBase, request.getRequestURL());

            // 把 segment_001.ts 等相对路径改写为**绝对路径**（含浏览器侧 origin + token）
            // 这样 Hls.js 解析时不会因为 base URL 拼接错位而失败
            String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            String rewritten = rewriteM3u8Segments(text, videoId, token, browserBase);

            log.info("m3u8_served, video_id={}, segments_count={}, in_size={}, out_size={}",
                videoId, countLines(text, ".ts"), content.length,
                rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

            return ResponseEntity.ok()
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .header("Cache-Control", "no-store, max-age=0")
                .body(rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("m3u8_serve_failed, video_id={}", videoId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 推断浏览器侧的 base URL（scheme + host + port）
     * 优先级：
     *   1. X-Forwarded-Proto + X-Forwarded-Host（反向代理标准头）
     *   2. Origin（浏览器跨域时带）
     *   3. Referer（去掉 path）
     *   4. request.getRequestURL()（最后兜底）
     */
    private String inferBrowserBase(jakarta.servlet.http.HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        String host = request.getHeader("X-Forwarded-Host");
        if (proto == null) proto = request.getHeader("X-Forwarded-Scheme");
        if (host == null) host = request.getHeader("X-Forwarded-Server");

        if (proto == null || host == null) {
            // 退化用 Origin / Referer
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.isBlank()) {
                return origin;  // Origin 形如 https://host:port
            }
            String referer = request.getHeader("Referer");
            if (referer != null && !referer.isBlank()) {
                int pathStart = referer.indexOf("/", referer.indexOf("//") + 2);
                if (pathStart > 0) return referer.substring(0, pathStart);
                return referer;
            }
        } else {
            return proto + "://" + host;
        }

        // 最后兜底：用 request URL
        String url = request.getRequestURL().toString();
        int pathStart = url.indexOf("/", url.indexOf("//") + 2);
        if (pathStart > 0) return url.substring(0, pathStart);
        return url;
    }

    /**
     * HLS segment（.ts）代理
     */
    @GetMapping(value = "/{video_id}/segment/{filename:.+}", produces = "video/mp2t")
    public ResponseEntity<byte[]> getSegment(
            @PathVariable("video_id") Long videoId,
            @PathVariable("filename") String filename,
            @RequestParam("token") String token) {
        // 1. 验证 token
        try {
            jwtTokenProvider.parseAndValidate(token);
        } catch (Exception e) {
            log.warn("segment_token_invalid, video_id={}, file={}, error={}",
                videoId, filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 安全检查：filename 只能包含 segment_*.ts 这种
        if (!filename.matches("segment_\\d+\\.ts")) {
            log.warn("segment_filename_invalid, video_id={}, filename={}", videoId, filename);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        try {
            String objectName = String.format("videos/%d/hls/%s", videoId, filename);
            InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(videosBucket).object(objectName).build());
            byte[] content = stream.readAllBytes();
            stream.close();
            return ResponseEntity.ok()
                .header("Content-Type", "video/mp2t")
                .header("Cache-Control", "public, max-age=300")
                .body(content);
        } catch (Exception e) {
            log.error("segment_serve_failed, video_id={}, file={}", videoId, filename, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 把 m3u8 里 segment_001.ts 等相对路径改为**绝对路径**（含浏览器侧 origin + token）
     *
     * 关键修复：之前用 `/api/v1/playback/{id}/segment/...` 相对路径，
     * Hls.js 用 m3u8 的 base URL 拼接，相对路径解析容易出错（特别是反向代理场景下
     * m3u8 URL 端口和实际访问端口不一致）。
     *
     * 改用绝对路径后，Hls.js 直接拉完整 URL，不会做 base URL 拼接。
     */
    private String rewriteM3u8Segments(String m3u8, long videoId, String token, String browserBase) {
        String base = browserBase.endsWith("/") ? browserBase.substring(0, browserBase.length() - 1) : browserBase;
        // 匹配 segment_xxx.ts 整行（不含 #EXTINF / #EXT-X-*）
        return m3u8.replaceAll(
            "(?m)^(segment_\\d+\\.ts)$",
            String.format("%s/api/v1/playback/%d/segment/$1?token=%s", base, videoId, token));
    }

    private int countLines(String text, String ext) {
        return (int) java.util.Arrays.stream(text.split("\n"))
            .filter(l -> l.trim().endsWith(ext))
            .count();
    }

    /**
     * 获取 HLS AES 密钥
     */
    @GetMapping("/{video_id}/key")
    public ResponseEntity<byte[]> getKey(
        @PathVariable("video_id") Long videoId,
        @RequestHeader("X-Session-Token") String sessionToken
    ) {
        // 实际生产：校验 sessionToken + 返回 16 字节 AES 密钥
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) (i + 1);

        return ResponseEntity.status(HttpStatus.OK)
            .header("Cache-Control", "no-store")
            .header("Content-Type", "application/octet-stream")
            .body(key);
    }

    /**
     * 上报播放日志
     */
    @PostMapping("/{video_id}/log")
    public ResponseEntity<Void> reportLog(
        @PathVariable("video_id") Long videoId,
        @RequestBody PlaybackLogRequest request,
        @RequestHeader("X-User-Id") Long userId
    ) {
        // 实际生产：写入 play_log 表 + 异步上报安全审计
        log.info("playback_log, video_id={}, user_id={}, event={}, progress={}s",
            videoId, userId, request.getEvent(), request.getProgressSec());

        // 验证 session
        if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.noContent().build();
    }

    @Data
    public static class PlaybackLogRequest {
        private String sessionId;
        private String event;
        private String timestamp;
        private Integer progressSec;
    }
}