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
 * 最大尝试次数：6 次（= 5 个退避档位 + 1 次直接进 DLQ）。
 * 配置项 {@code edam.webhook.retry.max-attempts} 可以调小，
 * 但**调大会被夹到 6** —— 超过就没有对应的延迟队列了（见
 * {@link #getEffectiveMaxAttempts()}）。
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

    @Value("${edam.webhook.retry.max-attempts:6}")
    private int maxAttempts;

    @Value("${edam.webhook.retry.enabled:true}")
    private boolean enabled;

    /**
     * 实际生效的最大尝试次数。
     *
     * <p><b>为什么要 clamp</b>（2026-11-14 代码审计第七轮）：
     * 退避档位只有 {@code backoffMillis.length} 个，每个档位对应
     * <b>一个声明出来的延迟队列</b>（{@code edam.webhook.retry.delay.{n}}，
     * 见 {@code RabbitConfig}）。
     *
     * <p>而重试是按"当前失败次数"选档位的：第 n 次失败要用第 n 档队列。
     * 所以第 n 次失败时 n 必须 ≤ 档位数，否则消息被投到
     * <b>没有声明、也没有绑定</b>的路由键 ⇒ broker 静默丢弃
     * ⇒ 既不重试也不进 DLQ，运维在库里查不到任何痕迹。
     *
     * <p>而 {@code max-attempts} 是个可配置项 —— 配成 7 就会踩到。
     * ⇒ 这里把它<b>夹到 {@code 档位数 + 1}</b>：
     * 最多用满 5 个退避档位，第 6 次失败进 DLQ。
     *
     * <p>顺带修掉一个 off-by-one：原来默认值是 5，于是
     * "第 5 次失败 → 12 小时后重试" 这一档（{@code backoffMillis[4]}）
     * <b>永远用不到</b> —— 第 5 次失败直接进 DLQ。
     * 默认值改成 6 之后，5 个档位（1 分钟 / 5 分钟 / 30 分钟 / 2 小时 / 12 小时）
     * 才与类注释里写的策略一致。
     */
    public int getEffectiveMaxAttempts() {
        return Math.min(maxAttempts, backoffMillis.length + 1);
    }

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
        if (attempt >= getEffectiveMaxAttempts()) {
            return -1;  // 进入死信队列
        }
        int idx = Math.min(attempt - 1, backoffMillis.length - 1);
        return backoffMillis[idx];
    }

    /**
     * 是否应该重试
     */
    public boolean shouldRetry(int attempt) {
        return enabled && attempt < getEffectiveMaxAttempts();
    }

    /**
     * 退避档位数量 —— 用于按档位声明延迟队列（见 {@code RabbitConfig}）。
     */
    public int getBackoffCount() {
        return backoffMillis.length;
    }
}