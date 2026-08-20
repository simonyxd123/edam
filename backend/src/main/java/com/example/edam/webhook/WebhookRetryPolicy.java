package com.example.edam.webhook;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Webhook 重试策略（v3.2 V-6）
 *
 * 指数退避（Exponential Backoff）：
 * 第 1 次失败 → 1 分钟后重试
 * 第 2 次失败 → 5 分钟后
 * 第 3 次失败 → 30 分钟后
 * 第 4 次失败 → 2 小时后
 * 第 5 次失败 → 12 小时后
 * 第 6 次失败 → 进入死信队列 + 告警
 *
 * 最大重试：5 次
 */
@Configuration
@Getter
public class WebhookRetryPolicy {

    public static final int MAX_ATTEMPTS = 5;

    /** 重试间隔（毫秒），索引表示第几次失败 */
    private final long[] backoffMillis = {
        60_000L,         // 1 分钟
        300_000L,        // 5 分钟
        1_800_000L,      // 30 分钟
        7_200_000L,      // 2 小时
        43_200_000L      // 12 小时
    };

    @Value("${edam.webhook.retry.max-attempts:5}")
    private int maxAttempts;

    @Value("${edam.webhook.retry.enabled:true}")
    private boolean enabled;

    /**
     * 获取第 N 次失败后的重试间隔
     *
     * @param attempt 当前已失败次数（从 1 开始）
     * @return 重试间隔毫秒数；若超过最大次数返回 -1（应进入 DLQ）
     */
    public long getBackoffMillis(int attempt) {
        if (attempt < 1) {
            return backoffMillis[0];
        }
        if (attempt >= maxAttempts) {
            return -1;  // 进入死信队列
        }
        int idx = Math.min(attempt - 1, backoffMillis.length - 1);
        return backoffMillis[idx];
    }

    /**
     * 是否应该重试
     */
    public boolean shouldRetry(int attempt) {
        return enabled && attempt < maxAttempts;
    }
}