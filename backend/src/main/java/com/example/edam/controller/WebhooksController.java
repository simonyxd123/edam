package com.example.edam.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Webhook Controller（v3.2 V-1 补全）
 * 对应 openapi.yaml tag: webhooks
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "webhooks", description = "回调注册")
public class WebhooksController {

    @GetMapping("/webhooks")
    @Operation(summary = "当前用户注册的回调列表")
    public List<Map<String, Object>> list(@RequestHeader("X-User-Id") Long userId) {
        log.info("webhook_list user_id={}", userId);
        return List.of();
    }

    @PostMapping("/webhooks")
    @Operation(summary = "注册回调")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody CreateWebhookRequest request,
            @RequestHeader("X-User-Id") Long userId) {

        log.info("webhook_create user_id={} url={}", userId, request.url);
        // 生成 secret（仅创建时返回一次）
        String secret = "whsec_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> response = new HashMap<>();
        response.put("id", System.currentTimeMillis());
        response.put("url", request.url);
        response.put("events", request.events);
        response.put("secret", secret);
        response.put("status", "active");
        response.put("created_at", java.time.OffsetDateTime.now().toString());
        return ResponseEntity.status(201).body(response);
    }

    @DeleteMapping("/webhooks/{webhook_id}")
    @Operation(summary = "注销回调")
    public ResponseEntity<Void> delete(
            @PathVariable("webhook_id") Long webhookId,
            @RequestHeader("X-User-Id") Long userId) {
        log.info("webhook_delete user_id={} webhook_id={}", userId, webhookId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/webhooks/{webhook_id}/deliveries")
    @Operation(summary = "回调投递历史")
    public List<Map<String, Object>> deliveries(
            @PathVariable("webhook_id") Long webhookId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "1") int page) {
        log.info("webhook_deliveries user_id={} webhook_id={}", userId, webhookId);
        return List.of();
    }

    @Data
    public static class CreateWebhookRequest {
        private String url;
        private List<String> events;
        private String description;
    }
}