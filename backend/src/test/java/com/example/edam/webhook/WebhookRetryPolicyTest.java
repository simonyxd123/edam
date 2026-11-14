package com.example.edam.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebhookRetryPolicy} 的单测。
 *
 * <p><b>盯的是什么</b>：退避档位与"最大尝试次数"必须对得上。
 *
 * <p>每个退避档位对应**一个声明出来的延迟队列**
 * （{@code edam.webhook.retry.delay.{n}}，见 {@code RabbitConfig}），
 * 而重试是按"当前失败次数"选档位的。所以失败次数一旦超过档位数，
 * 消息会被投到**没有声明也没有绑定**的路由键 ⇒ broker 静默丢弃
 * ⇒ 既不重试也不进 DLQ，运维在库里查不到任何痕迹。
 */
class WebhookRetryPolicyTest {

    private WebhookRetryPolicy policy(int maxAttempts) {
        WebhookRetryPolicy p = new WebhookRetryPolicy();
        ReflectionTestUtils.setField(p, "maxAttempts", maxAttempts);
        ReflectionTestUtils.setField(p, "enabled", true);
        return p;
    }

    @Test
    @DisplayName("默认策略用满 5 个退避档位，第 6 次失败进 DLQ")
    void defaultPolicyUsesAllBackoffTiers() {
        WebhookRetryPolicy p = policy(6);
        // 5 档退避（1 分钟 / 5 分钟 / 30 分钟 / 2 小时 / 12 小时）都要有值
        assertEquals(60_000L, p.getBackoffMillis(1));
        assertEquals(300_000L, p.getBackoffMillis(2));
        assertEquals(1_800_000L, p.getBackoffMillis(3));
        assertEquals(7_200_000L, p.getBackoffMillis(4));
        assertEquals(43_200_000L, p.getBackoffMillis(5));
        // 第 6 次失败不再重试
        assertFalse(p.shouldRetry(6));
        assertTrue(p.shouldRetry(5));
    }

    @Test
    @DisplayName("max-attempts 配得比档位数还大 ⇒ 被夹住，不会投到未声明的队列")
    void maxAttemptsIsClampedToDeclaredTiers() {
        // 配成 7：第 6 次失败会用第 6 档，而第 6 档队列**没有声明**
        WebhookRetryPolicy p = policy(7);
        assertEquals(6, p.getEffectiveMaxAttempts());
        // 第 5 次失败仍可用第 5 档
        assertTrue(p.shouldRetry(5));
        assertEquals(43_200_000L, p.getBackoffMillis(5));
        // 第 6 次失败直接进 DLQ —— 不会去用第 6 档
        assertFalse(p.shouldRetry(6));
        assertEquals(-1, p.getBackoffMillis(6));
    }

    @Test
    @DisplayName("max-attempts 配得比档位数小 ⇒ 按配置走")
    void smallerMaxAttemptsIsHonoured() {
        WebhookRetryPolicy p = policy(3);
        assertEquals(3, p.getEffectiveMaxAttempts());
        assertTrue(p.shouldRetry(2));
        assertFalse(p.shouldRetry(3));
    }

    @Test
    @DisplayName("重试关闭时一律不重试")
    void disabledPolicyNeverRetries() {
        WebhookRetryPolicy p = policy(6);
        ReflectionTestUtils.setField(p, "enabled", false);
        assertFalse(p.shouldRetry(1));
    }
}
