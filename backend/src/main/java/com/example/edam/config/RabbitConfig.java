package com.example.edam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.edam.webhook.WebhookRetryPolicy;
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

import java.util.ArrayList;
import java.util.List;

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

    /**
     * 死信队列的**路由键**（不是队列名）。
     *
     * <p>此前 {@code sendToDlq()} 把<b>队列名</b>当路由键发出去
     * （{@code convertAndSend(exchange, queueName, ...)}），
     * 而绑定用的是 {@code "dlq"} —— 路由键对不上，消息被 broker
     * <b>静默丢弃</b>（未开 mandatory 就不报错）。
     * 结果：失败的通知既不重试也不进死信，就那么消失了。
     */
    public static final String WEBHOOK_RETRY_DLQ_ROUTING_KEY = "dlq";

    /**
     * 延迟队列前缀 —— 每个退避档位一个队列：{@code edam.webhook.retry.delay.{n}}。
     *
     * <p>为什么不是一个队列 + per-message TTL：那样会<b>队头阻塞</b> ——
     * 一条 12 小时的延迟消息排在队头，后面 1 分钟的消息要等 12 小时才到期。
     * 每个档位一个<b>固定 TTL</b> 的队列就没有这个问题
     * （同一队列内消息按到达顺序依次到期）。
     */
    public static final String WEBHOOK_RETRY_DELAY_QUEUE_PREFIX = "edam.webhook.retry.delay.";

    /** 第 n 档退避的延迟队列名 */
    public static String delayQueueName(int attempt) {
        return WEBHOOK_RETRY_DELAY_QUEUE_PREFIX + attempt;
    }

    /** 第 n 档退避的延迟队列路由键 */
    public static String delayRoutingKey(int attempt) {
        return WEBHOOK_RETRY_DELAY_QUEUE_PREFIX + attempt;
    }

    /** 视频 / 文档预处理任务队列（与 edam_worker 约定的命名） */
    public static final String VIDEO_PREPROCESS_QUEUE = "q.video.preprocess";
    public static final String VIDEO_PREPROCESS_ROUTING = "video.preprocess";
    public static final String DOCUMENT_PREPROCESS_QUEUE = "q.document.preprocess";
    public static final String DOCUMENT_PREPROCESS_ROUTING = "document.preprocess";
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
    public Binding videoPreprocessBinding() {
        return BindingBuilder.bind(videoPreprocessQueue()).to(tasksExchange()).with(VIDEO_PREPROCESS_ROUTING);
    }

    @Bean
    public Binding documentPreprocessBinding() {
        return BindingBuilder.bind(documentPreprocessQueue()).to(tasksExchange()).with(DOCUMENT_PREPROCESS_ROUTING);
    }

    // 注：原先这里还有一条 q.watermark 队列（+ watermarkQueue / watermarkBinding）。
    // 它**从来没有生产者** —— 后端从不往 edam.tasks 的 "watermark" 路由发消息，
    // 而真实的盲水印嵌入走的是 q.document.preprocess（DocumentProcessor._watermark
    // → WatermarkProcessor.embed_*）。也就是说 q.watermark 是一条
    // "已声明、已绑定、已被 Worker 消费、已被 Prometheus 监控、但永远是空的"队列 ——
    // 留着它，运维看监控会以为这条链路是活的。
    // v3.4.1 删除。

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
        return BindingBuilder.bind(webhookRetryDlq())
            .to(webhookRetryExchange()).with(WEBHOOK_RETRY_DLQ_ROUTING_KEY);
    }

    /**
     * 延迟队列 —— 每个退避档位一个，固定 TTL，到期后死信回重试队列。
     *
     * <p>消息在这里等满 TTL，broker 自动把它投到
     * {@code edam.webhook.retry} 交换机、路由键
     * {@link #WEBHOOK_RETRY_ROUTING_KEY}，也就是重试消费者真正监听的那个队列。
     * 用 broker 的 TTL 而不是消费者线程 sleep —— 后者会占着线程，
     * 最长要占 12 小时。
     */
    @Bean
    public List<Queue> webhookRetryDelayQueues(WebhookRetryPolicy retryPolicy) {
        List<Queue> queues = new ArrayList<>();
        for (int attempt = 1; attempt <= retryPolicy.getBackoffCount(); attempt++) {
            long ttl = retryPolicy.getBackoffMillis(attempt);
            if (ttl <= 0) continue;
            queues.add(buildDelayQueue(attempt, ttl));
        }
        return queues;
    }

    @Bean
    public List<Binding> webhookRetryDelayBindings(WebhookRetryPolicy retryPolicy) {
        List<Binding> bindings = new ArrayList<>();
        for (int attempt = 1; attempt <= retryPolicy.getBackoffCount(); attempt++) {
            long ttl = retryPolicy.getBackoffMillis(attempt);
            if (ttl <= 0) continue;
            bindings.add(BindingBuilder
                .bind(buildDelayQueue(attempt, ttl))
                .to(webhookRetryExchange())
                .with(delayRoutingKey(attempt)));
        }
        return bindings;
    }

    /**
     * 构造第 n 档退避的延迟队列。
     *
     * <p>队列与绑定都要用**同一个** Queue 定义（RabbitAdmin 按参数声明，
     * 两边不一致会报 PRECONDITION_FAILED），所以抽成一个方法。
     */
    private Queue buildDelayQueue(int attempt, long ttlMillis) {
        return QueueBuilder.durable(delayQueueName(attempt))
            .withArgument("x-message-ttl", ttlMillis)
            .withArgument("x-dead-letter-exchange", WEBHOOK_RETRY_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", WEBHOOK_RETRY_ROUTING_KEY)
            .build();
    }
}