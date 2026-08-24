package com.example.edam.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（v3.2 V-6 Webhook 重试）
 *
 * Spring AMQP 默认 passive declaration：listener 只检查队列是否存在，
 * 不会自动创建。RabbitAdmin 启动时会自动声明所有 Queue/Exchange/Binding bean。
 *
 * 生产应改用 RabbitMQ Management UI / terraform 管理拓扑，避免代码声明漂移。
 */
@Configuration
public class RabbitConfig {

    public static final String WEBHOOK_RETRY_EXCHANGE = "edam.webhook.retry";
    public static final String WEBHOOK_RETRY_QUEUE = "edam.webhook.retry.queue";
    public static final String WEBHOOK_RETRY_ROUTING_KEY = "edam.webhook.retry";
    public static final String WEBHOOK_RETRY_DLQ = "edam.webhook.retry.dlq";

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
     * Webhook 重试交换机（Direct）
     */
    @Bean
    public DirectExchange webhookRetryExchange() {
        return new DirectExchange(WEBHOOK_RETRY_EXCHANGE, true, false);
    }

    /**
     * Webhook 重试队列
     * durable=true：RabbitMQ 重启不丢
     */
    @Bean
    public Queue webhookRetryQueue() {
        return QueueBuilder.durable(WEBHOOK_RETRY_QUEUE).build();
    }

    /**
     * Webhook 死信队列（人工介入）
     */
    @Bean
    public Queue webhookRetryDlq() {
        return QueueBuilder.durable(WEBHOOK_RETRY_DLQ).build();
    }

    @Bean
    public Binding webhookRetryBinding(Queue webhookRetryQueue, DirectExchange webhookRetryExchange) {
        return BindingBuilder.bind(webhookRetryQueue).to(webhookRetryExchange).with(WEBHOOK_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding webhookDlqBinding(Queue webhookRetryDlq, DirectExchange webhookRetryExchange) {
        return BindingBuilder.bind(webhookRetryDlq).to(webhookRetryExchange).with("dlq");
    }
}