package com.example.edam.webhook;

import com.example.edam.model.WebhookDelivery;
import com.example.edam.repository.WebhookDeliveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Webhook 投递服务（v3.2 V-6）
 *
 * 流程：
 * 1. 接收事件 + 目标 URL
 * 2. HMAC-SHA256 签名 payload
 * 3. 同步 HTTP POST 投递
 * 4. 成功 → 记录 WebhookDelivery (status=success)
 * 5. 失败 → 根据 retryPolicy 调度重试
 * 6. 重试 N 次失败 → 进入死信队列 + 告警
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDeliveryService {

    private final RabbitTemplate rabbitTemplate;
    private final WebhookRetryPolicy retryPolicy;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;

    @Value("${edam.webhook.retry.exchange:edam.webhook.retry}")
    private String retryExchange;

    @Value("${edam.webhook.retry.queue:edam.webhook.retry.queue}")
    private String retryQueue;

    @Value("${edam.webhook.retry.dlq:edam.webhook.retry.dlq}")
    private String dlqQueue;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /**
     * 投递 webhook（带重试调度）
     *
     * @param url       目标 URL
     * @param secret    HMAC 签名密钥
     * @param payload   投递内容（已序列化）
     * @param webhookId Webhook 配置 ID
     * @param event     事件类型
     */
    @Async("webhookExecutor")
    public void deliver(String url, String secret, String payload, Long webhookId, String event) {
        // 签名
        String signature = signHmacSha256(secret, payload);

        // 投递
        DeliveryResult result = doPost(url, payload, signature);

        // 记录
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhookId(webhookId);
        delivery.setEvent(event);
        delivery.setPayload(payload);
        delivery.setResponseStatus(result.statusCode);
        delivery.setResponseBody(result.body);
        delivery.setDeliveredAt(Instant.now());
        deliveryRepository.insert(delivery);

        // 失败调度重试
        if (!result.success) {
            scheduleRetry(url, secret, payload, webhookId, event, 1, delivery.getId());
        } else {
            log.info("webhook_delivered webhook_id={} event={} status={}", webhookId, event, result.statusCode);
        }
    }

    /**
     * 调度重试
     */
    private void scheduleRetry(String url, String secret, String payload,
                                Long webhookId, String event, int attempt, Long deliveryId) {
        if (!retryPolicy.shouldRetry(attempt)) {
            // 进入死信队列
            sendToDlq(webhookId, event, payload, attempt, "max_attempts_exceeded");
            log.error("webhook_dlq webhook_id={} event={} attempts={}",
                webhookId, event, attempt);
            return;
        }

        long backoffMs = retryPolicy.getBackoffMillis(attempt);
        log.warn("webhook_retry_scheduled webhook_id={} event={} attempt={} delay_ms={}",
            webhookId, event, attempt, backoffMs);

        // 发送到 RabbitMQ 延迟队列（TTL = backoffMs）
        rabbitTemplate.convertAndSend(retryExchange, retryQueue, Map.of(
            "webhook_id", webhookId,
            "event", event,
            "url", url,
            "secret", secret,
            "payload", payload,
            "attempt", attempt + 1,  // 下次重试的次数
            "original_delivery_id", deliveryId,
            "retry_at_ms", System.currentTimeMillis() + backoffMs
        ));
    }

    private void sendToDlq(Long webhookId, String event, String payload, int attempt, String reason) {
        rabbitTemplate.convertAndSend(retryExchange, dlqQueue, Map.of(
            "webhook_id", webhookId,
            "event", event,
            "payload", payload,
            "attempts", attempt,
            "reason", reason,
            "failed_at_ms", System.currentTimeMillis()
        ));
    }

    /**
     * 实际 HTTP POST
     */
    private DeliveryResult doPost(String url, String payload, String signature) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-EDAM-Signature", "sha256=" + signature)
                .header("X-EDAM-Timestamp", String.valueOf(System.currentTimeMillis() / 1000))
                .header("User-Agent", "EDAM-Webhook/3.2.0")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = httpResponse.statusCode();
            boolean success = code >= 200 && code < 300;
            String body = httpResponse.body();
            // 截断到 1KB
            if (body.length() > 1024) {
                body = body.substring(0, 1024);
            }
            return new DeliveryResult(success, code, body);
        } catch (Exception e) {
            log.error("webhook_post_failed url={} error={}", url, e.getMessage());
            return new DeliveryResult(false, 0, e.getMessage());
        }
    }

    /**
     * HMAC-SHA256 签名
     */
    private String signHmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sigBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sigBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed", e);
        }
    }

    /**
     * 投递结果
     */
    private record DeliveryResult(boolean success, int statusCode, String body) {}
}