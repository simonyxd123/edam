package com.example.edam.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录限流器（v3.2 V-3）
 *
 * 基于 Bucket4j 令牌桶算法实现：
 * - 单 IP 每分钟 5 次（perIpRate）
 * - 单工号每分钟 3 次（perEmployeeRate）
 *
 * 规则：
 * - 任一维度超阈值 → 拒绝请求（429）
 * - 单 IP 单日 100 次 → 要求图形验证码（外部触发，本组件只生成标志）
 * - 连续 5 次密码错误 → 账号锁定 30 分钟（外部触发）
 *
 * 对应 OpenAPI §4.1.1 /auth/login 描述
 */
@Slf4j
@Component
public class LoginRateLimiter {

    @Value("${edam.rate-limit.login.per-ip:5}")
    private int perIpRate;

    @Value("${edam.rate-limit.login.per-employee:3}")
    private int perEmployeeRate;

    @Value("${edam.rate-limit.login.window-seconds:60}")
    private int windowSeconds;

    /** IP 维度限流桶 */
    private final ConcurrentHashMap<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    /** 工号维度限流桶 */
    private final ConcurrentHashMap<String, Bucket> employeeBuckets = new ConcurrentHashMap<>();

    /**
     * 检查 IP + 工号双维度限流
     *
     * @param clientIp   客户端 IP
     * @param employeeNo 工号（可为空，预检阶段）
     * @return 限流结果
     */
    public RateLimitResult check(String clientIp, String employeeNo) {
        // 维度 1：单 IP 限流
        Bucket ipBucket = ipBuckets.computeIfAbsent(clientIp, this::newIpBucket);
        ConsumptionProbe ipProbe = ipBucket.tryConsumeAndReturnRemaining(1);

        if (!ipProbe.isConsumed()) {
            long waitSeconds = ipProbe.getNanosToWaitForRefill() / 1_000_000_000L + 1;
            log.warn("login_rate_limit_ip client_ip={} wait_seconds={}", clientIp, waitSeconds);
            return RateLimitResult.denied("ip", waitSeconds, ipProbe.getRemainingTokens());
        }

        // 维度 2：单工号限流（仅当工号非空时）
        if (employeeNo != null && !employeeNo.isBlank()) {
            Bucket empBucket = employeeBuckets.computeIfAbsent(employeeNo, this::newEmployeeBucket);
            ConsumptionProbe empProbe = empBucket.tryConsumeAndReturnRemaining(1);

            if (!empProbe.isConsumed()) {
                long waitSeconds = empProbe.getNanosToWaitForRefill() / 1_000_000_000L + 1;
                log.warn("login_rate_limit_employee employee_no={} wait_seconds={}", employeeNo, waitSeconds);
                return RateLimitResult.denied("employee", waitSeconds, empProbe.getRemainingTokens());
            }
        }

        return RateLimitResult.allowed(ipProbe.getRemainingTokens());
    }

    /** IP 桶：每分钟 5 个令牌 */
    private Bucket newIpBucket(String ip) {
        Bandwidth limit = Bandwidth.builder()
            .capacity(perIpRate)
            .refillIntervally(perIpRate, Duration.ofSeconds(windowSeconds))
            .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /** 工号桶：每分钟 3 个令牌 */
    private Bucket newEmployeeBucket(String employeeNo) {
        Bandwidth limit = Bandwidth.builder()
            .capacity(perEmployeeRate)
            .refillIntervally(perEmployeeRate, Duration.ofSeconds(windowSeconds))
            .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * 限流结果
     */
    public static class RateLimitResult {
        private final boolean allowed;
        private final String deniedDimension;  // "ip" / "employee" / null
        private final long retryAfterSeconds;
        private final long remainingTokens;

        private RateLimitResult(boolean allowed, String deniedDimension, long retryAfterSeconds, long remainingTokens) {
            this.allowed = allowed;
            this.deniedDimension = deniedDimension;
            this.retryAfterSeconds = retryAfterSeconds;
            this.remainingTokens = remainingTokens;
        }

        public static RateLimitResult allowed(long remaining) {
            return new RateLimitResult(true, null, 0, remaining);
        }

        public static RateLimitResult denied(String dimension, long retryAfter, long remaining) {
            return new RateLimitResult(false, dimension, retryAfter, remaining);
        }

        public boolean isAllowed() { return allowed; }
        public String getDeniedDimension() { return deniedDimension; }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
        public long getRemainingTokens() { return remainingTokens; }
    }
}