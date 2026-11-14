package com.example.edam.exception;

import lombok.Getter;

/**
 * 账号锁定异常（v3.2 V-3）
 *
 * 触发场景：连续 5 次密码错误 → 账号锁定 30 分钟
 *
 * <p><b>2026-11-14 代码审计第五轮</b>：此前无论什么原因锁定，
 * 都往客户端报"Retry after 1800 seconds" —— 那是个假承诺
 * （系统压根没有到期解锁机制，锁是永久的）。现在：
 * <ul>
 *   <li>自动锁定 ⇒ 报<b>真实</b>的剩余秒数（随时间递减，到点自动解锁）；</li>
 *   <li>管理员手动锁定（无到期时间）⇒ 报"需联系管理员"，不谎报秒数。</li>
 * </ul>
 */
@Getter
public class AccountLockedException extends RuntimeException {

    /**
     * 剩余锁定秒数。
     *
     * <p>{@link Long#MAX_VALUE} 表示"没有到期时间"（管理员手动锁定）。
     */
    private final long remainingLockSeconds;

    public AccountLockedException(long remainingLockSeconds) {
        super(remainingLockSeconds >= Long.MAX_VALUE
            ? "账号已被锁定，请联系管理员解锁。"
            : String.format("账号已锁定，请在 %d 秒后重试。", remainingLockSeconds));
        this.remainingLockSeconds = remainingLockSeconds;
    }

    /**
     * 是否为"不会自动到期"的锁定（管理员手动锁定）。
     */
    public boolean isPermanent() {
        return remainingLockSeconds >= Long.MAX_VALUE;
    }
}
