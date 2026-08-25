package com.example.edam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑 + 消息序列化（v3.2 V-6 Webhook 重试）
 *
 * 关键：rabbitTemplate 的 MessageConverter 改成 Jackson2JsonMessageConverter，
 * 否则 convertAndSend(Map) 默认走 Java ObjectOutputStream 序列化，
 * Python / Node / Go 等异构消费者解不开。
 */
@Configuration
public class RabbitConfig {

    public static final String WEBHOOK_RETRY_EXCHANGE = "edam.webhook.retry";
    public static final String WEBHOOK_RETRY_QUEUE = "edam.webhook.retry.queue";
    public static final String WEBHOOK_RETRY_ROUTING_KEY = "edam.webhook.retry";
    public static final String WEBHOOK_RETRY_DLQ = "edam.webhook.retry.dlq";

    /** 视频 / 文档预处理任务队列（与 edam_worker 约定的命名） */
    public static final String VIDEO_PREPROCESS_QUEUE = "q.video.preprocess";
    public static final String VIDEO_PREPROCESS_ROUTING = "video.preprocess";
    public static final String DOCUMENT_PREPROCESS_QUEUE = "q.document.preprocess";
    public static final String DOCUMENT_PREPROCESS_ROUTING = "document.preprocess";
    public static final String WATERMARK_QUEUE = "q.watermark";
    public static final String WATERMARK_ROUTING = "watermark";
    public static final String TASKS_EXCHANGE = "edam.tasks";

    /**
     * 显式注册 RabbitAdmin（auto-startup=true 让它连接后自动声明）
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    /**
     * 统一 JSON 消息转换器（替代默认的 Java 序列化）
     * - convertAndSend(Object) → 自动 Jackson 序列化（字节流以 utf-8 文本）
     * - 异构消费者（Python / Go / Node）能直接 json.loads()
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 把 RabbitTemplate 接上 JSON converter
     * （Spring Boot 自动注入的 RabbitTemplate 默认用 SimpleMessageConverter，需要覆盖）
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    /**
     * 任务交换机（Direct）
     */
    @Bean
    public DirectExchange tasksExchange() {
        return new DirectExchange(TASKS_EXCHANGE, true, false);
    }

    @Bean
    public Queue videoPreprocessQueue() {
        return QueueBuilder.durable(VIDEO_PREPROCESS_QUEUE).build();
    }

    @Bean
    public Queue documentPreprocessQueue() {
        return QueueBuilder.durable(DOCUMENT_PREPROCESS_QUEUE).build();
    }

    @Bean
    public Queue watermarkQueue() {
        return QueueBuilder.durable(WATERMARK_QUEUE).build();
    }

    @Bean
    public Binding videoPreprocessBinding() {
        return BindingBuilder.bind(videoPreprocessQueue()).to(tasksExchange()).with(VIDEO_PREPROCESS_ROUTING);
    }

    @Bean
    public Binding documentPreprocessBinding() {
        return BindingBuilder.bind(documentPreprocessQueue()).to(tasksExchange()).with(DOCUMENT_PREPROCESS_ROUTING);
    }

    @Bean
    public Binding watermarkBinding() {
        return BindingBuilder.bind(watermarkQueue()).to(tasksExchange()).with(WATERMARK_ROUTING);
    }

    /**
     * Webhook 重试交换机（Direct）
     */
    @Bean
    public DirectExchange webhookRetryExchange() {
        return new DirectExchange(WEBHOOK_RETRY_EXCHANGE, true, false);
    }

    /**
     * Webhook 重试队列
     */
    @Bean
    public Queue webhookRetryQueue() {
        return QueueBuilder.durable(WEBHOOK_RETRY_QUEUE).build();
    }

    /**
     * Webhook 死信队列
     */
    @Bean
    public Queue webhookRetryDlq() {
        return QueueBuilder.durable(WEBHOOK_RETRY_DLQ).build();
    }

    @Bean
    public Binding webhookRetryBinding() {
        return BindingBuilder.bind(webhookRetryQueue()).to(webhookRetryExchange()).with(WEBHOOK_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding webhookDlqBinding() {
        return BindingBuilder.bind(webhookRetryDlq()).to(webhookRetryExchange()).with("dlq");
    }
}