package com.example.edam.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Webhook 重试消费者（v3.2 V-6）
 *
 * 监听 RabbitMQ 延迟队列，到达时间后投递
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRetryConsumer {

    private final WebhookDeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    /**
     * 监听重试队列
     */
    @RabbitListener(queues = "${edam.webhook.retry.queue:edam.webhook.retry.queue}")
    public void onRetryMessage(Map<String, Object> message) {
        try {
            String url = (String) message.get("url");
            String secret = (String) message.get("secret");
            String payload = (String) message.get("payload");
            Long webhookId = ((Number) message.get("webhook_id")).longValue();
            String event = (String) message.get("event");
            int attempt = ((Number) message.get("attempt")).intValue();

            log.info("webhook_retry_received webhook_id={} attempt={}", webhookId, attempt);
            deliveryService.deliver(url, secret, payload, webhookId, event);
        } catch (Exception e) {
            log.error("webhook_retry_failed", e);
        }
    }

    /**
     * 监听死信队列（人工介入）
     */
    @RabbitListener(queues = "${edam.webhook.retry.dlq:edam.webhook.retry.dlq}")
    public void onDlqMessage(Map<String, Object> message) {
        log.error("webhook_dlq_alert webhook_id={} event={} payload_size={}",
            message.get("webhook_id"), message.get("event"),
            ((String) message.get("payload")).length());
        // v3.2 占位：实际生产应触发 P0 告警（钉钉/电话） + 工单系统
    }
}