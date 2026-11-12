package com.example.edam.controller;

import com.example.edam.security.CurrentUserId;
import com.example.edam.model.Notification;
import com.example.edam.model.NotificationPreference;
import com.example.edam.repository.NotificationPreferenceRepository;
import com.example.edam.repository.NotificationRepository;
import com.example.edam.util.PageParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
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

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;

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
    @AuditOp(op = AuditOpType.LIST, resourceType = "notification")
    public Map<String, Object> list(
        @RequestParam(required = false) Boolean is_read,
        @RequestParam(required = false) String type,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size,
        @CurrentUserId Long userId) {

        log.info("notifications_list user_id={} is_read={} type={}", userId, is_read, type);

        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId);
        if (is_read != null) {
            wrapper.eq(Notification::getIsRead, is_read ? 1 : 0);
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(Notification::getType, type.trim());
        }
        // 过期通知不再展示
        wrapper.and(w -> w.isNull(Notification::getExpiresAt)
                         .or().gt(Notification::getExpiresAt, LocalDateTime.now()));
        wrapper.orderByDesc(Notification::getCreatedAt);

        Page<Notification> result = notificationRepository.selectPage(
            new Page<>(PageParams.clampPage(page), PageParams.clampSize(page_size)), wrapper);

        Map<String, Object> response = new HashMap<>();
        response.put("items", result.getRecords().stream().map(this::toItem).toList());
        response.put("pagination", Map.of(
            "page", result.getCurrent(),
            "page_size", result.getSize(),
            "total", result.getTotal(),
            "total_pages", result.getPages()
        ));
        response.put("unread_count", notificationRepository.countUnread(userId));
        return response;
    }

    @PostMapping("/notifications/{notif_id}/read")
    @Operation(summary = "标记已读")
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "notification", resourceId = "#notifId",
        detail = "action=mark_read")
    public ResponseEntity<Void> markRead(
        @PathVariable("notif_id") Long notifId,
        @CurrentUserId Long userId) {
        // 带 user_id 条件 —— 否则任何人都能把别人的通知标成已读
        int updated = notificationRepository.markRead(notifId, userId);
        if (updated == 0) {
            // 别人的通知 / 不存在的通知 —— 404 而不是静默 204
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "通知不存在或不属于当前用户");
        }
        log.info("notification_read notif_id={} user_id={}", notifId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    @Operation(summary = "全部标记已读")
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "notification",
        detail = "action=mark_all_read")
    public Map<String, Object> markAllRead(@CurrentUserId Long userId) {
        int updated = notificationRepository.markAllRead(userId);
        log.info("notifications_read_all user_id={} updated={}", userId, updated);
        return Map.of("updated_count", updated);
    }

    @GetMapping("/notifications/preferences")
    @Operation(summary = "获取通知偏好")
    @AuditOp(op = AuditOpType.DETAIL, resourceType = "notification_preference")
    public Map<String, Object> getPreferences(@CurrentUserId Long userId) {
        NotificationPreference pref = preferenceRepository.findByUserId(userId);
        if (pref == null) {
            // 还没有偏好记录 → 返回默认偏好（**不落库**，等用户改）
            return defaultPreferences();
        }
        return toPreferencesResponse(pref);
    }

    @PutMapping("/notifications/preferences")
    @Operation(summary = "更新通知偏好")
    @AuditOp(op = AuditOpType.CONFIG_CHANGE, resourceType = "notification_preference")
    public Map<String, Object> updatePreferences(
        @RequestBody Map<String, Object> body,
        @CurrentUserId Long userId) {

        // 此前这里是**原样回显请求体** —— 用户以为设置保存了，
        // 其实什么都没落库。现在真的写 notification_preferences 表。
        NotificationPreference pref = preferenceRepository.findByUserId(userId);
        boolean creating = pref == null;
        if (creating) {
            pref = new NotificationPreference();
            pref.setUserId(userId);
        }

        if (body.containsKey("channels")) {
            pref.setChannels(toJson(body.get("channels")));
        }
        if (body.containsKey("types")) {
            pref.setTypes(toJson(body.get("types")));
        }
        if (body.containsKey("quiet_hours")) {
            Object qh = body.get("quiet_hours");
            Map<?, ?> quiet = qh instanceof Map ? (Map<?, ?>) qh : Map.of();
            pref.setQuietHoursEnabled(Boolean.TRUE.equals(quiet.get("enabled")) ? 1 : 0);
            Object start = quiet.get("start");
            Object end = quiet.get("end");
            pref.setQuietHoursStart(start == null ? null : String.valueOf(start));
            pref.setQuietHoursEnd(end == null ? null : String.valueOf(end));
        }

        if (creating) {
            preferenceRepository.insert(pref);
        } else {
            preferenceRepository.updateById(pref);
        }
        log.info("notification_preferences_updated user_id={} created={}", userId, creating);
        return toPreferencesResponse(pref);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 默认偏好（用户还没有偏好记录时）。
     */
    private Map<String, Object> defaultPreferences() {
        return Map.of(
            "channels", Map.of(
                "email", true, "sms", false, "im_webhook", true, "in_app", true),
            "types", Map.of(
                "approval", true, "key_alert", true, "driver_alert", true,
                "compliance", true, "system", true),
            "quiet_hours", Map.of(
                "enabled", false, "start", "22:00:00", "end", "08:00:00")
        );
    }

    private Map<String, Object> toPreferencesResponse(NotificationPreference pref) {
        Map<String, Object> response = new HashMap<>();
        response.put("channels", parseJson(pref.getChannels()));
        response.put("types", parseJson(pref.getTypes()));
        response.put("quiet_hours", Map.of(
            "enabled", pref.getQuietHoursEnabled() != null && pref.getQuietHoursEnabled() == 1,
            "start", pref.getQuietHoursStart() == null ? "22:00:00" : pref.getQuietHoursStart(),
            "end", pref.getQuietHoursEnd() == null ? "08:00:00" : pref.getQuietHoursEnd()
        ));
        return response;
    }

    private Map<String, Object> toItem(Notification n) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", n.getId());
        item.put("type", n.getType());
        item.put("title", n.getTitle());
        item.put("content", n.getContent());
        item.put("related_resource_type", n.getRelatedResourceType());
        item.put("related_resource_id", n.getRelatedResourceId());
        item.put("is_read", n.getIsRead() != null && n.getIsRead() == 1);
        item.put("created_at", n.getCreatedAt());
        item.put("expires_at", n.getExpiresAt());
        return item;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法序列化为 JSON: " + e.getMessage());
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("notification_preference_json_invalid: {}", e.getMessage());
            return Map.of();
        }
    }
}