package com.example.edam.metrics;

import com.example.edam.config.RabbitConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TranscodeQueueMetrics} 单测。
 *
 * <p><b>为什么这个用例必须存在</b>：出过一次用户可见故障 ——
 * Worker 进程没起来，转码任务卡在 RabbitMQ 队列里没人消费，
 * 而<b>没有任何告警</b>能提前发现（队列深度只在健康检查的 JSON 里，
 * Prometheus 抓不到；已有的积压告警阈值是 1000 条，现场只有 1 条）。
 *
 * <p>本用例锁住两条不变量：
 * <ol>
 *   <li>队列深度能变成真正的 Prometheus 指标（能被告警用）</li>
 *   <li>RabbitMQ 不可达时<b>保留上一次的值</b>、并把 reachable 打成 0 ——
 *       绝不能置 0：置 0 等于告诉告警"队列已清空"，把真实故障掩盖掉</li>
 * </ol>
 */
class TranscodeQueueMetricsTest {

    private RabbitAdmin rabbitAdmin;
    private MeterRegistry registry;
    private TranscodeQueueMetrics metrics;

    @BeforeEach
    void setUp() {
        rabbitAdmin = mock(RabbitAdmin.class);
        registry = new SimpleMeterRegistry();
        metrics = new TranscodeQueueMetrics(rabbitAdmin, registry);
    }

    @Test
    @DisplayName("三个业务队列的深度指标都被注册了")
    void registersGaugeForEveryWatchedQueue() {
        when(rabbitAdmin.getQueueInfo(anyString()))
            .thenReturn(new QueueInformation(RabbitConfig.VIDEO_PREPROCESS_QUEUE, 1, 0));

        metrics.refresh();

        for (String queue : metrics.watchedQueues()) {
            assertEquals(1, registry.get("edam_transcode_queue_depth")
                .tag("queue", queue).gauge().value(), 0.001,
                "队列 " + queue + " 必须有深度指标，否则配不出积压告警");
        }
        assertEquals(1, registry.get("edam_transcode_queue_reachable").gauge().value(), 0.001);
    }

    @Test
    @DisplayName("RabbitMQ 正常 ⇒ 深度被刷新成真实值")
    void refreshUpdatesDepth() {
        when(rabbitAdmin.getQueueInfo(anyString()))
            .thenReturn(new QueueInformation(RabbitConfig.VIDEO_PREPROCESS_QUEUE, 7, 0));

        metrics.refresh();

        assertEquals(7, metrics.currentDepths().get(RabbitConfig.VIDEO_PREPROCESS_QUEUE));
        assertEquals(1, metrics.reachable());
    }

    @Test
    @DisplayName("RabbitMQ 不可达 ⇒ 保留上次的值 + reachable=0（绝不置 0）")
    void unreachableKeepsLastKnownDepth() {
        when(rabbitAdmin.getQueueInfo(anyString()))
            .thenReturn(new QueueInformation(RabbitConfig.VIDEO_PREPROCESS_QUEUE, 7, 0));
        metrics.refresh();

        when(rabbitAdmin.getQueueInfo(anyString()))
            .thenThrow(new IllegalStateException("rabbitmq down"));
        metrics.refresh();

        assertEquals(7, metrics.currentDepths().get(RabbitConfig.VIDEO_PREPROCESS_QUEUE),
            "不可达时必须保留上次的值 —— 置 0 会伪装成'队列已清空'");
        assertEquals(0, metrics.reachable(),
            "不可达标记必须打成 0，否则'积压'和'查不到'两种故障分不开");
    }
}
