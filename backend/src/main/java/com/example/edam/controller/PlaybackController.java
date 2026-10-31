package com.example.edam.controller;

import com.example.edam.model.VideoResource;
import io.jsonwebtoken.Claims;
import com.example.edam.security.JwtTokenProvider;
import com.example.edam.service.AuditService;
import com.example.edam.service.VideoService;
import com.example.edam.security.CurrentUserId;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import jakarta.servlet.http.HttpServletRequest;
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
    private final AuditService auditService;

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
        @CurrentUserId Long userId,
        HttpServletRequest request
    ) {
        VideoResource video = videoService.getById(videoId);

        // 写审计日志：视频预览
        auditService.log(userId, "preview", "video", videoId, "success",
            "ip=" + request.getRemoteAddr() + ", title=" + video.getTitle(),
            request.getRemoteAddr(), request.getHeader("User-Agent"));

        // 1. 签发短时 JWT
        String sessionId = UUID.randomUUID().toString();
        String token = jwtTokenProvider.createAccessToken(userId, sessionId,
                List.of("ROLE_VIEWER"), List.of("video:read", "playback:read"));

        // 2. 生成签名 URL
        long expires = Instant.now().getEpochSecond() + 600;  // 10 分钟
        String m3u8Url = String.format("/api/v1/playback/%d/playlist.m3u8?token=%s",
            videoId, token);
        // segment URL 模板（给前端展示用，实际由 m3u8 内容决定）

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
     * Hls.js 不能直接拉 MinIO 的 HLS（缺少 Authorization 头），
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
        //
        // 先验 token 再打日志：验不过的请求也要留下痕迹，但**不能记 token 本身**
        // （连哈希都不打 —— 见项目安全约定），只记 video_id。
        io.jsonwebtoken.Claims claims;
        try {
            claims = jwtTokenProvider.parseAndValidate(token);
        } catch (Exception e) {
            log.warn("m3u8_token_invalid, video_id={}, error={}", videoId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String requesterId = subjectOf(claims);
        log.info("m3u8_endpoint_hit, video_id={}, requester_id={}, token_len={}, remote_addr={}, x_forwarded_host={}",
            videoId, requesterId, token.length(), request.getRemoteAddr(), request.getHeader("X-Forwarded-Host"));

        String objectName = String.format("videos/%d/hls/playlist.m3u8", videoId);
        try {
            byte[] content;
            // try-with-resources：原来 readAllBytes() 抛异常时 close() 永不执行，
            // MinIO 的 HTTP 连接直接泄漏，并发播放出错时会耗尽连接池
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder().bucket(videosBucket).object(objectName).build())) {
                content = stream.readAllBytes();
            }

            // 把 segment_001.ts 等相对路径改写为**根相对路径**（/api/v1/...）
            // 同时把 EXT-X-KEY 的 URI="key.bin" 改写为后端 key 端点（带 token）
            //
            // 为什么是"根相对"而不是"绝对路径"：
            // 绝对路径需要先猜出浏览器侧的 origin（scheme + host + port），
            // 而这个猜测**不可靠** —— nginx 的 `proxy_set_header X-Forwarded-Host $host`
            // 里 `$host` **不含端口**（含端口的是 `$http_host`），
            // 于是 `https://host:65173` 会被猜成 `https://host`（丢了 65173）。
            // 猜错的后果：m3u8 本身能下（它是同源相对路径），
            // 但 m3u8 里的分片/密钥地址指向了另一个 origin/端口 ⇒ 拉不到 ⇒
            // hls.js 拿不到任何 HTTP 响应（超时）⇒ 前端只能报 "HTTP ??"。
            //
            // 根相对路径由 **hls.js 用 manifest 自身的 URL** 解析：
            // manifest 是浏览器自己按同源拉下来的，它的 origin/端口**一定是对的**，
            // 所以分片/密钥地址也就一定对 —— 不依赖后端猜端口。
            String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            String rewritten = rewriteM3u8(text, videoId, token);

            log.info("m3u8_served, video_id={}, requester_id={}, segments_count={}, has_ext_x_key={}, in_size={}, out_size={}",
                videoId, requesterId, countLines(text, ".ts"), text.contains("#EXT-X-KEY"), content.length,
                rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

            return ResponseEntity.ok()
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .header("Cache-Control", "no-store, max-age=0")
                .body(rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 区分两种 404：
            //   - MinIO 里真的没这个对象（转码没跑 / 产物被删）
            //   - MinIO 本身连不上（网络/凭据问题）
            // 原来一律 404，把"对象存储挂了"也报成"文件不存在"，
            // 运维查 MinIO 时会被带偏。
            if (isMinioUnreachable(e)) {
                log.error("m3u8_serve_failed_minio_unreachable, video_id={}", videoId, e);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            log.error("m3u8_serve_failed, video_id={}, object={}", videoId, objectName, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 判断异常是不是"MinIO 本身不可达"（而不是"对象不存在"）。
     *
     * <p>MinIO 对"对象不存在"抛 {@code ErrorResponseException}，
     * code = {@code NoSuchKey}；对"连不上 / 5xx / 鉴权失败"抛别的异常
     * （{@code ServerException} / {@code InsufficientDataException} /
     * {@code IOException} 之类）。
     */
    private boolean isMinioUnreachable(Exception e) {
        if (e instanceof io.minio.errors.ErrorResponseException) {
            String code = ((io.minio.errors.ErrorResponseException) e).errorResponse().code();
            // NoSuchKey / NoSuchBucket 之外的 S3 错误码更可能是服务端问题
            return code == null || !("NoSuchKey".equals(code) || "NoSuchBucket".equals(code));
        }
        // 连接类异常（UnknownHost / ConnectException / SocketTimeout …）
        return e instanceof java.io.IOException
            || e.getCause() instanceof java.io.IOException;
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
        Claims claims;
        try {
            claims = jwtTokenProvider.parseAndValidate(token);
        } catch (Exception e) {
            log.warn("segment_token_invalid, video_id={}, file={}, error={}",
                videoId, filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String requesterId = subjectOf(claims);

        // 安全检查：filename 只能包含 segment_*.ts 这种
        if (!filename.matches("segment_\\d+\\.ts")) {
            log.warn("segment_filename_invalid, video_id={}, filename={}", videoId, filename);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        try {
            String objectName = String.format("videos/%d/hls/%s", videoId, filename);
            byte[] content;
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder().bucket(videosBucket).object(objectName).build())) {
                content = stream.readAllBytes();
            }
            return ResponseEntity.ok()
                .header("Content-Type", "video/mp2t")
                .header("Cache-Control", "public, max-age=300")
                .body(content);
        } catch (Exception e) {
            // 带上请求者：同一个视频被不同账号播放时，出问题时能区分是谁的请求
            log.error("segment_serve_failed, video_id={}, file={}, requester_id={}",
                videoId, filename, requesterId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 把 m3u8 改写成 Hls.js 可直接消费的形态：
     * 1. segment_001.ts 等相对路径 → **根相对路径** {@code /api/v1/playback/{id}/segment/...}（带 token）
     * 2. EXT-X-KEY 的 URI="key.bin" → 后端 key 端点根相对地址（带 token）
     *
     * <p><b>为什么用根相对路径</b>（而不是早期版本用的绝对路径）：
     * 绝对路径要后端先猜出浏览器侧的 origin（scheme + host + **port**），
     * 而这个猜测在反向代理下**不可靠**：
     * nginx 的 {@code proxy_set_header X-Forwarded-Host $host} 里，
     * {@code $host} **不含端口**（含端口的是 {@code $http_host}），
     * 于是 {@code https://host:65173} 被猜成 {@code https://host} —— 端口丢了。
     *
     * <p>丢端口的后果很隐蔽：manifest 本身是同源相对路径，能正常下载；
     * 但 m3u8 里的分片/密钥地址指向了另一个 origin ⇒ 浏览器连不上 ⇒
     * hls.js **收不到任何 HTTP 响应**（只能等超时）⇒ 前端只能报 {@code HTTP ??}。
     *
     * <p>根相对路径由 hls.js 用 **manifest 自身的 URL** 解析 ——
     * manifest 是浏览器按同源拉下来的，它的 origin 和端口**必然正确**，
     * 所以分片/密钥地址也必然正确。后端不再需要猜端口。
     */
    private String rewriteM3u8(String m3u8, long videoId, String token) {
        // 匹配 segment_xxx.ts 整行（不含 #EXTINF / #EXT-X-*）
        String rewritten = m3u8.replaceAll(
            "(?m)^(segment_\\d+\\.ts)$",
            String.format("/api/v1/playback/%d/segment/$1?token=%s", videoId, token));
        // EXT-X-KEY 的 URI 指向 key.bin（worker 写的是相对路径），
        // 改成后端 key 端点 —— Hls.js 不能带自定义头，所以走 query token
        rewritten = rewritten.replaceAll(
            "URI=\"key\\.bin\"",
            String.format("URI=\"/api/v1/playback/%d/key?token=%s\"", videoId, token));
        return rewritten;
    }

    private int countLines(String text, String ext) {
        return (int) java.util.Arrays.stream(text.split("\n"))
            .filter(l -> l.trim().endsWith(ext))
            .count();
    }

    /**
     * 获取 HLS AES-128 密钥
     *
     * 鉴权：query string token（与 playlist.m3u8 / segment 同一套机制）。
     * Hls.js 拉 key 时**不会带自定义请求头**，所以不能用
     * {@code @RequestHeader("X-Session-Token")} —— 那样会直接 400。
     *
     * 密钥来源：Worker 转码时生成的 16 字节随机 key，存在 MinIO
     * {@code videos/{id}/key.bin}（刻意放在 hls/ 目录之外，
     * 避免被 segment 代理暴露）。
     */
    @GetMapping("/{video_id}/key")
    public ResponseEntity<byte[]> getKey(
        @PathVariable("video_id") Long videoId,
        @RequestParam("token") String token
    ) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parseAndValidate(token);
        } catch (Exception e) {
            log.warn("key_token_invalid, video_id={}, error={}", videoId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String requesterId = subjectOf(claims);

        String objectName = String.format("videos/%d/key.bin", videoId);
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(videosBucket).object(objectName).build())) {
            byte[] key = stream.readAllBytes();
            if (key.length != 16) {
                log.error("hls_key_invalid_length, video_id={}, len={}", videoId, key.length);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            log.info("hls_key_served, video_id={}, requester_id={}", videoId, requesterId);
            return ResponseEntity.status(HttpStatus.OK)
                .header("Cache-Control", "no-store")
                .header("Content-Type", "application/octet-stream")
                .body(key);
        } catch (Exception e) {
            log.error("hls_key_serve_failed, video_id={}, object={}, requester_id={}",
                videoId, objectName, requesterId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 从已解析的 claims 里取请求者 id（= JWT 的 subject），取不到就返回 null。
     *
     * <p>用于日志：同一个视频可能被多个账号播放，出问题时得能区分是谁的请求。
     * **只取 subject，不打 token 本身**（项目安全约定：日志里不得出现 token）。
     */
    private String subjectOf(Claims claims) {
        return claims == null ? null : claims.getSubject();
    }

    /**
     * 上报播放日志
     */
    @PostMapping("/{video_id}/log")
    public ResponseEntity<Void> reportLog(
        @PathVariable("video_id") Long videoId,
        @RequestBody PlaybackLogRequest request,
        @CurrentUserId Long userId
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