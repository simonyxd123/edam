package com.example.edam.exception;

import lombok.Getter;

/**
 * 限流超出异常（v3.2 V-3）
 *
 * 触发场景：
 * - IP 维度超限
 * - 工号维度超限
 */
@Getter
public class RateLimitExceededException extends RuntimeException {
    private final String dimension;     // "ip" / "employee"
    private final long retryAfterSeconds;
    private final long remainingTokens;

    public RateLimitExceededException(String dimension, long retryAfterSeconds, long remainingTokens) {
        super(String.format("Rate limit exceeded on %s. Retry after %d seconds.", dimension, retryAfterSeconds));
        this.dimension = dimension;
        this.retryAfterSeconds = retryAfterSeconds;
        this.remainingTokens = remainingTokens;
    }
}