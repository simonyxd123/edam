package com.example.edam.exception;

import lombok.Getter;

/**
 * 账号锁定异常（v3.2 V-3）
 *
 * 触发场景：连续 5 次密码错误 → 账号锁定 30 分钟
 */
@Getter
public class AccountLockedException extends RuntimeException {
    private final long remainingLockSeconds;

    public AccountLockedException(long remainingLockSeconds) {
        super(String.format("Account locked. Retry after %d seconds.", remainingLockSeconds));
        this.remainingLockSeconds = remainingLockSeconds;
    }
}