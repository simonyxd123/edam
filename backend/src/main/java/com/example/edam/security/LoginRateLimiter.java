package com.example.edam.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p><b>内存有界</b>：原先两个 {@code ConcurrentHashMap} 永不清理，
 * 而 key 是客户端可控的 IP / 工号 —— 攻击者每次换一个伪造的
 * X-Forwarded-For 就能往 map 里塞一条新记录，几万次请求即撑爆堆。
 * 现在：
 * <ul>
 *   <li>{@link #evictStaleBuckets()} 定时清理超过 2 个窗口未访问的桶</li>
 *   <li>总量硬上限 {@link #maxEntries}：超了按 LRU 淘汰最旧的</li>
 * </ul>
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

    /** 桶总数硬上限（IP + 工号两个维度合计） */
    @Value("${edam.rate-limit.login.max-entries:10000}")
    private int maxEntries;

    /** IP 维度限流桶 */
    private final ConcurrentHashMap<String, BucketEntry> ipBuckets = new ConcurrentHashMap<>();

    /** 工号维度限流桶 */
    private final ConcurrentHashMap<String, BucketEntry> employeeBuckets = new ConcurrentHashMap<>();

    /**
     * 桶条目：令牌桶 + 最后访问时间戳。
     */
    private static final class BucketEntry {
        final Bucket bucket;
        final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());

        BucketEntry(Bucket bucket) {
            this.bucket = bucket;
        }
    }

    /**
     * 检查 IP + 工号双维度限流
     *
     * @param clientIp   客户端 IP
     * @param employeeNo 工号（可为空，预检阶段）
     * @return 限流结果
     */
    public RateLimitResult check(String clientIp, String employeeNo) {
        // 维度 1：单 IP 限流
        BucketEntry ipEntry = ipBuckets.computeIfAbsent(clientIp, k -> {
            enforceMaxEntries();
            return new BucketEntry(newIpBucket());
        });
        ipEntry.lastAccess.set(System.currentTimeMillis());
        ConsumptionProbe ipProbe = ipEntry.bucket.tryConsumeAndReturnRemaining(1);

        if (!ipProbe.isConsumed()) {
            long waitSeconds = ipProbe.getNanosToWaitForRefill() / 1_000_000_000L + 1;
            log.warn("login_rate_limit_ip client_ip={} wait_seconds={}", clientIp, waitSeconds);
            return RateLimitResult.denied("ip", waitSeconds, ipProbe.getRemainingTokens());
        }

        // 维度 2：单工号限流（仅当工号非空时）
        if (employeeNo != null && !employeeNo.isBlank()) {
            BucketEntry empEntry = employeeBuckets.computeIfAbsent(employeeNo, k -> {
                enforceMaxEntries();
                return new BucketEntry(newEmployeeBucket());
            });
            empEntry.lastAccess.set(System.currentTimeMillis());
            ConsumptionProbe empProbe = empEntry.bucket.tryConsumeAndReturnRemaining(1);

            if (!empProbe.isConsumed()) {
                long waitSeconds = empProbe.getNanosToWaitForRefill() / 1_000_000_000L + 1;
                log.warn("login_rate_limit_employee employee_no={} wait_seconds={}", employeeNo, waitSeconds);
                return RateLimitResult.denied("employee", waitSeconds, empProbe.getRemainingTokens());
            }
        }

        return RateLimitResult.allowed(ipProbe.getRemainingTokens());
    }

    /**
     * 定时清理超过 2 个窗口未访问的桶。
     *
     * <p>没有这一步，map 会随着伪造 IP 无限增长。
     */
    @Scheduled(fixedDelayString = "${edam.rate-limit.login.cleanup-interval-ms:60000}")
    public void evictStaleBuckets() {
        long ttl = Math.max(1L, windowSeconds) * 2_000L;
        long now = System.currentTimeMillis();
        ipBuckets.entrySet().removeIf(e -> now - e.getValue().lastAccess.get() > ttl);
        employeeBuckets.entrySet().removeIf(e -> now - e.getValue().lastAccess.get() > ttl);
        log.debug("rate_limit_cleanup ip_remaining={} emp_remaining={}",
            ipBuckets.size(), employeeBuckets.size());
    }

    /**
     * 桶总数硬上限：超了就淘汰最久未访问的若干条。
     *
     * <p>这是定时清理之外的第二道保险 —— 万一定时任务没跑起来
     * （比如应用刚启动就遭遇洪峰），内存也不会无界增长。
     */
    private void enforceMaxEntries() {
        int total = ipBuckets.size() + employeeBuckets.size();
        if (total < maxEntries) {
            return;
        }
        int toRemove = Math.max(1, total - maxEntries * 9 / 10);
        removeOldest(ipBuckets, toRemove);
        removeOldest(employeeBuckets, toRemove);
        log.warn("rate_limit_entries_overflow, total={}, max={}, evicted={}",
            total, maxEntries, toRemove);
    }

    private void removeOldest(ConcurrentHashMap<String, BucketEntry> map, int count) {
        for (int i = 0; i < count; i++) {
            String oldestKey = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<String, BucketEntry> e : map.entrySet()) {
                long ts = e.getValue().lastAccess.get();
                if (ts < oldestTs) {
                    oldestTs = ts;
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            map.remove(oldestKey);
        }
    }

    /** IP 桶：每分钟 5 个令牌 */
    private Bucket newIpBucket() {
        Bandwidth limit = Bandwidth.builder()
            .capacity(perIpRate)
            .refillIntervally(perIpRate, Duration.ofSeconds(windowSeconds))
            .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /** 工号桶：每分钟 3 个令牌 */
    private Bucket newEmployeeBucket() {
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