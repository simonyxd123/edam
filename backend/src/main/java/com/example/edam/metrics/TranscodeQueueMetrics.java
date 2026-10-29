package com.example.edam.metrics;

import com.example.edam.config.RabbitConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 转码队列积压指标。
 *
 * <p><b>为什么必须存在</b>：出过一次**用户可见的故障** ——
 * 视频点播放报 {@code 网络异常，正在重试（1/3）… (HTTP 404)}，
 * 根因是 <b>Worker 进程没起来</b>，任务卡在 RabbitMQ 队列里没人消费。
 *
 * <p>而当时**没有任何告警**能提前发现这件事：
 * <ul>
 *   <li>队列深度只在 {@code /health/ready} 的 JSON 里（{@code rabbitmq.queue_depth}），
 *       Prometheus 抓不到 JSON 里的字段 → 配不出告警；</li>
 *   <li>已有的 {@code RabbitMQQueueDepth} 规则阈值是 1000 条 ——
 *       而卡死的现场只有 <b>1 条</b>消息，阈值等于永远不触发。</li>
 * </ul>
 *
 * <p>本组件把队列深度变成**真正的 Prometheus 指标**，
 * 让"队列里有消息但没人消费"能被告警提前发现，而不是等用户点播放才发现。
 *
 * <p><b>指标语义</b>：
 * <ul>
 *   <li>{@code edam_transcode_queue_depth{queue="..."}} —— 队列 ready 消息数。
 *       取不到时**保留上一次的值**（而不是置 0）—— 置 0 会让
 *       "积压告警"误以为队列已清空，实际上这时候 RabbitMQ 根本连不上。</li>
 *   <li>{@code edam_transcode_queue_reachable} —— 1 = 上次刷新成功，
 *       0 = RabbitMQ 不可达。<b>用来区分"队列积压"和"队列查询失败"</b>，
 *       否则 RabbitMQ 一挂，积压告警和可达性告警会同时炸，噪音掩盖真问题。</li>
 * </ul>
 */
@Slf4j
@Component
public class TranscodeQueueMetrics {

    /** 需要监控的业务队列（不含 webhook 重试队列 —— 那是另一条链路）。 */
    private static final List<String> WATCHED_QUEUES = List.of(
        RabbitConfig.VIDEO_PREPROCESS_QUEUE,
        RabbitConfig.DOCUMENT_PREPROCESS_QUEUE,
        RabbitConfig.WATERMARK_QUEUE
    );

    private final RabbitAdmin rabbitAdmin;
    private final Map<String, AtomicInteger> depths = new LinkedHashMap<>();
    private final AtomicInteger reachable = new AtomicInteger(1);

    public TranscodeQueueMetrics(RabbitAdmin rabbitAdmin, MeterRegistry meterRegistry) {
        this.rabbitAdmin = rabbitAdmin;
        for (String queue : WATCHED_QUEUES) {
            AtomicInteger depth = new AtomicInteger(0);
            depths.put(queue, depth);
            Gauge.builder("edam_transcode_queue_depth", depth, AtomicInteger::get)
                .description("转码队列 ready 消息数（0 = 已清空；RabbitMQ 不可达时保留上一次的值）")
                .tag("queue", queue)
                .register(meterRegistry);
        }
        Gauge.builder("edam_transcode_queue_reachable", reachable, AtomicInteger::get)
            .description("RabbitMQ 队列查询是否成功（1 = 成功，0 = 不可达）")
            .register(meterRegistry);
    }

    /**
     * 定时刷新队列深度。
     *
     * <p><b>为什么用定时刷新而不是在 scrape 时现查</b>：
     * Prometheus 的 scrape 是有超时的，RabbitMQ 抖动时
     * {@code rabbitAdmin.getQueueInfo()} 会阻塞几秒 ——
     * 直接把阻塞甩进 scrape 线程，会让整个 actuator 端点超时，
     * 连带其它指标一起丢。定时刷新把阻塞隔离在自己的线程里。
     */
    @Scheduled(fixedDelayString = "${edam.metrics.queue-depth-interval-ms:30000}",
               initialDelayString = "${edam.metrics.queue-depth-initial-delay-ms:5000}")
    public void refresh() {
        boolean allOk = true;
        for (Map.Entry<String, AtomicInteger> entry : depths.entrySet()) {
            try {
                QueueInformation info = rabbitAdmin.getQueueInfo(entry.getKey());
                if (info == null) {
                    // 队列不存在（还没声明？）—— 保留上一次的值，不当成 0
                    allOk = false;
                    continue;
                }
                entry.getValue().set(info.getMessageCount());
            } catch (Exception e) {
                // 保留上一次的值：置 0 会伪装成"队列已清空"，把真实故障掩盖掉
                allOk = false;
                log.warn("queue_depth_probe_failed, queue={}", entry.getKey(), e);
            }
        }
        reachable.set(allOk ? 1 : 0);
    }

    /** 供测试与健康端点读取当前快照。 */
    public Map<String, Integer> currentDepths() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        depths.forEach((queue, depth) -> snapshot.put(queue, depth.get()));
        return snapshot;
    }

    /** RabbitMQ 当前是否可达（1/0）。 */
    public int reachable() {
        return reachable.get();
    }

    /** 暴露被监控的队列名，便于运维确认覆盖范围。 */
    public List<String> watchedQueues() {
        return Arrays.asList(WATCHED_QUEUES.toArray(new String[0]));
    }
}
