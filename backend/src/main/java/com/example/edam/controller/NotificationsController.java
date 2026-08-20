package com.example.edam.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
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

    @GetMapping("/ws/notifications")
    @Operation(summary = "WebSocket 长连接（实时通知）")
    public ResponseEntity<Void> websocketHandshake(
            @RequestParam String token) {
        // 实际 WebSocket 握手由 WebSocketConfig 的握手拦截器处理
        // 此端点占位返回 101（升级）或 426（升级必需）
        return ResponseEntity.status(426)
            .header(HttpHeaders.UPGRADE, "websocket")
            .header(HttpHeaders.CONNECTION, "Upgrade")
            .header("Sec-WebSocket-Protocol", "edam-notifications-v1")
            .build();
    }

    @GetMapping("/notifications")
    @Operation(summary = "当前用户通知列表")
    public Map<String, Object> list(
            @RequestParam(required = false) Boolean is_read,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int page_size,
            @RequestHeader("X-User-Id") Long userId) {

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
            @RequestHeader("X-User-Id") Long userId) {
        log.info("notification_read notif_id={} user_id={}", notifId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    @Operation(summary = "全部标记已读")
    public Map<String, Object> markAllRead(@RequestHeader("X-User-Id") Long userId) {
        log.info("notifications_read_all user_id={}", userId);
        return Map.of("updated_count", 0);
    }

    @GetMapping("/notifications/preferences")
    @Operation(summary = "获取通知偏好")
    public Map<String, Object> getPreferences(@RequestHeader("X-User-Id") Long userId) {
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
            @RequestHeader("X-User-Id") Long userId) {
        log.info("notification_preferences_updated user_id={}", userId);
        return body;
    }
}