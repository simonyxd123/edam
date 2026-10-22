package com.example.edam.controller;

import com.example.edam.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通知管理 Controller（v3.2 V-1 补全）
 * 对应 openapi.yaml tag: notifications
 *
 * WebSocket 端点（/ws/notifications）由 v3.2 WebSocketConfig 处理
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "notifications", description = "通知与消息")
public class NotificationsController {

    /**
     * 旧的 WebSocket 占位端点。
     *
     * <p><b>已废弃</b>：真正的 WebSocket 端点是 {@code /ws}（STOMP），
     * 由 {@code WebSocketConfig} 注册。这里的 {@code /ws/notifications}
     * 是个**普通 HTTP GET**，跟 WebSocket 握手毫无关系 ——
     * 返回 426「Upgrade Required」会让人以为是握手被拒，
     * 实际是路径根本不对。
     *
     * <p>现在明确返回 404 + 指向正确的端点。
     */
    @GetMapping("/ws/notifications")
    @Operation(summary = "（已废弃）请改用 WebSocket 端点 /ws")
    public ResponseEntity<Map<String, Object>> websocketHandshake(
            @RequestParam(required = false) String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "deprecated_endpoint");
        body.put("detail", "该端点不是 WebSocket 端点。真正的实时通知通道是 /ws"
            + "（STOMP over WebSocket），需用 Authorization 头或 ?token= 传 JWT，"
            + "订阅 /topic/notifications/* 主题。");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @GetMapping("/notifications")
    @Operation(summary = "当前用户通知列表")
    public Map<String, Object> list(
            @RequestParam(required = false) Boolean is_read,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int page_size,
            @CurrentUserId Long userId) {

        log.info("notifications_list user_id={} is_read={} type={}", userId, is_read, type);
        Map<String, Object> response = new HashMap<>();
        response.put("items", java.util.List.of());
        response.put("pagination", Map.of(
            "page", page, "page_size", page_size, "total", 0, "total_pages", 0
        ));
        response.put("unread_count", 0);
        return response;
    }

    @PostMapping("/notifications/{notif_id}/read")
    @Operation(summary = "标记已读")
    public ResponseEntity<Void> markRead(
            @PathVariable("notif_id") Long notifId,
            @CurrentUserId Long userId) {
        log.info("notification_read notif_id={} user_id={}", notifId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    @Operation(summary = "全部标记已读")
    public Map<String, Object> markAllRead(@CurrentUserId Long userId) {
        log.info("notifications_read_all user_id={}", userId);
        return Map.of("updated_count", 0);
    }

    @GetMapping("/notifications/preferences")
    @Operation(summary = "获取通知偏好")
    public Map<String, Object> getPreferences(@CurrentUserId Long userId) {
        return Map.of(
            "channels", Map.of("email", true, "sms", false, "im_webhook", true, "in_app", true),
            "types", Map.of("approval", true, "key_alert", true, "driver_alert", true, "compliance", true, "system", true),
            "quiet_hours", Map.of("enabled", true, "start", "22:00", "end", "08:00")
        );
    }

    @PutMapping("/notifications/preferences")
    @Operation(summary = "更新通知偏好")
    public Map<String, Object> updatePreferences(
            @RequestBody Map<String, Object> body,
            @CurrentUserId Long userId) {
        log.info("notification_preferences_updated user_id={}", userId);
        return body;
    }
}