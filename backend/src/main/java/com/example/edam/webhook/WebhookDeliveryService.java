package com.example.edam.webhook;

import com.example.edam.config.RabbitConfig;
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
        // 不跟随重定向：Java 的 HttpClient 默认就是 NEVER，这里写出来是为了
        // 明确意图 —— 一个公网地址 302 到 169.254.169.254 就是一次 SSRF。
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /** 回调响应体最多读这么多字节（超出部分直接丢弃）。 */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

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
        deliver(url, secret, payload, webhookId, event, 1);
    }

    /**
     * 投递 webhook（带重试调度），显式指定当前是第几次尝试。
     *
     * <p>attempt 必须**逐次透传**：原实现里 deliver 硬编码
     * {@code scheduleRetry(..., 1, ...)}，而重试消费者虽然从消息里
     * 取出了 attempt 却**完全没用** —— 于是每次重试 attempt 都重置成 1，
     * {@code shouldRetry(1)} 永远为 true → 无限重试风暴，
     * 永远进不了死信队列。
     *
     * @param attempt 当前是第几次尝试（首次 = 1）
     */
    @Async("webhookExecutor")
    public void deliver(String url, String secret, String payload, Long webhookId, String event, int attempt) {
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
        // 第几次尝试 —— 表里本来就有 retry_count 这一列，
        // 但 model 一直没映射它（2026-11-08 模型对账发现），
        // 于是投递历史里每条记录长得一模一样：
        // "失败了 3 次然后成功"和"一次就成功"分不出来。
        delivery.setRetryCount(attempt);
        deliveryRepository.insert(delivery);

        // 失败调度重试（attempt 逐次递增，不再重置为 1）
        if (!result.success) {
            scheduleRetry(url, secret, payload, webhookId, event, attempt, delivery.getId());
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

        // 发到**对应档位的延迟队列** —— 消息在那里等满 TTL，
        // 由 broker 死信回 edam.webhook.retry 交换机（见 RabbitConfig）。
        //
        // 注意第二个参数是**路由键**，不是队列名。此前这里传的是队列名
        // （"edam.webhook.retry.queue"），而绑定用的是 "edam.webhook.retry"
        // —— 路由键对不上，消息被 broker 静默丢弃，重试**从未发生过**。
        rabbitTemplate.convertAndSend(
            retryExchange,
            RabbitConfig.delayRoutingKey(attempt),
            Map.of(
                "webhook_id", webhookId,
                "event", event,
                "url", url,
                "secret", secret,
                "payload", payload,
                "attempt", attempt + 1,  // 下次重试的次数
                "original_delivery_id", deliveryId,
                "retry_at_ms", System.currentTimeMillis() + backoffMs
            ));

        // 把"下次重试时间"写回投递记录 —— 表里有 next_retry_at 这一列，
        // 但 model 之前没映射它（2026-11-08 模型对账发现），
        // 于是运维在投递历史里**看不出"这条还在等重试"**。
        if (deliveryId != null) {
            deliveryRepository.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WebhookDelivery>()
                    .eq(WebhookDelivery::getId, deliveryId)
                    .set(WebhookDelivery::getNextRetryAt,
                        Instant.now().plusMillis(backoffMs)));
        }
    }

    private void sendToDlq(Long webhookId, String event, String payload, int attempt, String reason) {
        // 同样是**路由键**（"dlq"），不是死信队列名 —— 见 RabbitConfig 的说明
        rabbitTemplate.convertAndSend(retryExchange, RabbitConfig.WEBHOOK_RETRY_DLQ_ROUTING_KEY, Map.of(
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
            // 投递前**再查一次** SSRF（2026-11-08）。
            // 注册时查过一次，但中间可能过了很久；主机名也可能在注册后
            // 被改 DNS 指向内网（DNS 重绑定）。每次投递前重查，
            // 把窗口压到最小 —— 查不到就**不投**（fail-closed），
            // 不能"检查失败就当没事"。
            com.example.edam.security.SsrfGuard.assertPublicUrl(url);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-EDAM-Signature", "sha256=" + signature)
                .header("X-EDAM-Timestamp", String.valueOf(System.currentTimeMillis() / 1000))
                .header("User-Agent", "EDAM-Webhook/3.2.0")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

            // 响应体**限量读**：原先用 BodyHandlers.ofString() —— 那个 handler
            // 会把整个响应读进内存，而截断到 1KB 是**读完之后**才做的。
            // 一个返回 1GB body 的（恶意或坏掉的）回调目标就能把后端 OOM 掉。
            // 现在流式读，最多取前 64KB。
            HttpResponse<java.io.InputStream> streamed = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
            int code = streamed.statusCode();
            boolean success = code >= 200 && code < 300;
            String body;
            try (java.io.InputStream in = streamed.body()) {
                byte[] buf = in.readNBytes(MAX_RESPONSE_BYTES);
                body = new String(buf, StandardCharsets.UTF_8);
            }
            // 截断到 1KB（存进 webhook_delivery.response_body）
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