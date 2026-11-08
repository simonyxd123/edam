package com.example.edam.controller;

import com.example.edam.security.CurrentUserId;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import com.example.edam.model.Webhook;
import com.example.edam.model.WebhookDelivery;
import com.example.edam.repository.WebhookDeliveryRepository;
import com.example.edam.repository.WebhookRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    private final WebhookRepository webhookRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;

    @GetMapping("/webhooks")
    @Operation(summary = "当前用户注册的回调列表")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('webhook:read')")
    @AuditOp(op = AuditOpType.LIST, resourceType = "webhook")
    public List<Map<String, Object>> list(@CurrentUserId Long userId) {
        List<Map<String, Object>> items = webhookRepository.findByOwner(userId).stream()
            .map(WebhooksController::toItem)
            .toList();
        log.info("webhook_list user_id={} count={}", userId, items.size());
        return items;
    }

    @PostMapping("/webhooks")
    @Operation(summary = "注册回调")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('webhook:manage')")
    @AuditOp(op = AuditOpType.CREATE, resourceType = "webhook",
        detail = "url=#request.url, events=#request.events")
    public ResponseEntity<Map<String, Object>> create(
        @RequestBody CreateWebhookRequest request,
        @CurrentUserId Long userId) {

        if (request.url == null || request.url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        if (request.events == null || request.events.isEmpty()) {
            throw new IllegalArgumentException("events 至少要订阅一个事件");
        }
        // SSRF 防护：回调地址会被服务端主动 POST，
        // 不允许指向内网 / 回环地址
        assertPublicUrl(request.url);

        log.info("webhook_create user_id={} url={}", userId, request.url);

        // 生成 secret（**明文只在创建时返回一次**，库里只存哈希）
        String secret = "whsec_" + UUID.randomUUID().toString().replace("-", "");

        Webhook webhook = new Webhook();
        webhook.setOwnerId(userId);
        webhook.setUrl(request.url.trim());
        webhook.setEvents(String.join(",", request.events));
        webhook.setSecretHash(sha256Hex(secret));
        webhook.setStatus(1);   // active
        webhook.setFailCount(0);
        webhook.setCreatedAt(java.time.LocalDateTime.now());
        webhookRepository.insert(webhook);

        Map<String, Object> response = toItem(webhook);
        response.put("secret", secret);   // 仅此一次
        return ResponseEntity.status(201).body(response);
    }

    @DeleteMapping("/webhooks/{webhook_id}")
    @Operation(summary = "注销回调")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('webhook:manage')")
    @AuditOp(op = AuditOpType.DELETE, resourceType = "webhook", resourceId = "#webhookId")
    public ResponseEntity<Void> delete(
        @PathVariable("webhook_id") Long webhookId,
        @CurrentUserId Long userId) {
        // 带 owner 校验 —— 否则任何人都能注销别人的回调
        Webhook webhook = webhookRepository.findByIdAndOwner(webhookId, userId);
        if (webhook == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "回调不存在或不属于当前用户");
        }
        webhookRepository.deleteById(webhookId);
        log.info("webhook_delete user_id={} webhook_id={}", userId, webhookId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/webhooks/{webhook_id}/deliveries")
    @Operation(summary = "回调投递历史")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('webhook:read')")
    @AuditOp(op = AuditOpType.LIST, resourceType = "webhook_delivery",
        resourceId = "#webhookId")
    public List<Map<String, Object>> deliveries(
        @PathVariable("webhook_id") Long webhookId,
        @CurrentUserId Long userId,
        @RequestParam(defaultValue = "1") int page) {
        // 归属校验：不能读别人的投递历史
        if (webhookRepository.findByIdAndOwner(webhookId, userId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "回调不存在或不属于当前用户");
        }
        LambdaQueryWrapper<WebhookDelivery> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WebhookDelivery::getWebhookId, webhookId)
               .orderByDesc(WebhookDelivery::getDeliveredAt);
        int size = Math.min(100, Math.max(1, 20 * Math.max(1, page)));
        List<Map<String, Object>> items = webhookDeliveryRepository
            .selectPage(new Page<>(Math.max(1, page), size), wrapper)
            .getRecords().stream()
            .map(WebhooksController::toDeliveryItem)
            .toList();
        log.info("webhook_deliveries user_id={} webhook_id={} count={}",
            userId, webhookId, items.size());
        return items;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static Map<String, Object> toItem(Webhook w) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", w.getId());
        item.put("url", w.getUrl());
        item.put("events", w.getEvents() == null
            ? List.of() : List.of(w.getEvents().split(",")));
        item.put("status", statusName(w.getStatus()));
        item.put("fail_count", w.getFailCount());
        item.put("last_delivered_at", w.getLastDeliveredAt());
        item.put("created_at", w.getCreatedAt());
        return item;
    }

    private static Map<String, Object> toDeliveryItem(WebhookDelivery d) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", d.getId());
        item.put("webhook_id", d.getWebhookId());
        item.put("event", d.getEvent());
        item.put("response_status", d.getResponseStatus());
        item.put("response_body", d.getResponseBody());
        item.put("delivered_at", d.getDeliveredAt());
        // 重试信息 —— 表里本来就有这两列，model 一直没映射（2026-11-08 对账发现）。
        // 没有它们，"失败了 3 次然后成功"和"一次就成功"在列表里长得一模一样，
        // 也看不出"这条还在等重试"。
        item.put("retry_count", d.getRetryCount());
        item.put("next_retry_at", d.getNextRetryAt());
        return item;
    }

    private static String statusName(Integer status) {
        if (status == null) return "unknown";
        return switch (status) {
            case 1 -> "active";
            case 2 -> "paused";
            case 3 -> "failed";
            default -> "unknown";
        };
    }

    private static String sha256Hex(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 回调地址必须指向公网 —— 服务端会主动 POST 过去，
     * 指向内网就是 **SSRF**（能扫内网、能打云厂商的元数据服务）。
     */
    private static void assertPublicUrl(String url) {
        // SSRF 校验统一走 SsrfGuard（2026-11-08 抽出去）：
        // 原先这里的实现只查 IPv4 字面量，IPv6 私网 / 主机名解析到内网 /
        // 十进制写法（2130706433 == 127.0.0.1）都能绕过。
        com.example.edam.security.SsrfGuard.assertPublicUrl(url);
    }

    @Data
    public static class CreateWebhookRequest {
        private String url;
        private List<String> events;
        private String description;
    }
}