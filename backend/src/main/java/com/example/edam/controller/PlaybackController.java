package com.example.edam.controller;

import com.example.edam.model.VideoResource;
import com.example.edam.security.JwtTokenProvider;
import com.example.edam.service.VideoService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        String m3u8Url = String.format("/api/v1/video/%d/playlist.m3u8?token=%s&expires=%d",
            videoId, token, expires);

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