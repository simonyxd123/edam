package com.example.edam.security;

import com.example.edam.exception.AccountLockedException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 账号锁定**到期自动解锁**的单测。
 *
 * <p>2026-11-14 代码审计第五轮发现：此前"连续 5 次密码错误 → 锁定 30 分钟"
 * 只会锁、不会解 —— {@code sys_user} 里没有到期时间字段，也没有任何地方
 * 把 {@code status} 从 3 改回 1 ⇒ 账号被**永久**锁死。
 *
 * <p>而"锁到可以被别人用 5 次错误密码打死的账号"这件事，
 * 在一个防泄密系统里是能主动利用的（工号可枚举），
 * 所以这一组用例盯的是"到期真的会解锁"。
 */
class AccountLockExpiryTest {

    private SysUserRepository userRepository;
    private LoginAttemptService service;

    /** 最小可用的 PlatformTransactionManager —— TransactionTemplate 需要它来开独立事务 */
    private final PlatformTransactionManager txManager = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op
        }
    };

    @BeforeEach
    void setUp() {
        userRepository = mock(SysUserRepository.class);
        service = new LoginAttemptService(userRepository, txManager);
    }

    private SysUser user(Integer status, OffsetDateTime lockedUntil, Integer failedCount) {
        SysUser u = new SysUser();
        u.setId(42L);
        u.setEmployeeNo("E000042");
        u.setStatus(status);
        u.setLockedUntil(lockedUntil);
        u.setFailedLoginCount(failedCount);
        return u;
    }

    @Test
    @DisplayName("锁还没到期 ⇒ 拒绝登录，并报**真实**的剩余秒数")
    void rejectsWhileLockIsActive() {
        SysUser u = user(3, OffsetDateTime.now().plusMinutes(10), 5);

        AccountLockedException ex = assertThrows(AccountLockedException.class,
            () -> service.assertLoginAllowed(u));

        assertTrue(ex.getRemainingLockSeconds() > 0);
        assertTrue(ex.getRemainingLockSeconds() <= 600);
        assertTrue(!ex.isPermanent());
        verify(userRepository, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("锁已到期 ⇒ 自动解锁并放行（这是本轮修的核心）")
    void autoUnlocksWhenExpired() {
        SysUser u = user(3, OffsetDateTime.now().minusSeconds(1), 5);

        assertDoesNotThrow(() -> service.assertLoginAllowed(u));

        assertEquals(1, u.getStatus());
        assertEquals(0, u.getFailedLoginCount());
        assertNull(u.getLockedUntil());
        verify(userRepository).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("管理员手动锁定（没有到期时间）⇒ **不自动解锁**")
    void adminLockNeverExpires() {
        SysUser u = user(3, null, 5);

        AccountLockedException ex = assertThrows(AccountLockedException.class,
            () -> service.assertLoginAllowed(u));

        assertTrue(ex.isPermanent());
        verify(userRepository, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("达到失败阈值 ⇒ 写入到期时间（30 分钟后自动解锁）")
    void recordFailureSetsExpiry() {
        SysUser u = user(1, null, 4);

        service.recordFailure(u, 5);

        assertEquals(3, u.getStatus());
        assertEquals(5, u.getFailedLoginCount());
        assertNotNull(u.getLockedUntil());
        long minutes = java.time.Duration.between(OffsetDateTime.now(), u.getLockedUntil()).toMinutes();
        assertTrue(minutes > 0 && minutes <= LoginAttemptService.LOCK_DURATION_MINUTES,
            "剩余锁定分钟数应在 1~" + LoginAttemptService.LOCK_DURATION_MINUTES + "，实际 " + minutes);
        verify(userRepository).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("未达阈值 ⇒ 不锁定，也不写到期时间")
    void belowThresholdDoesNotLock() {
        SysUser u = user(1, null, 1);

        service.recordFailure(u, 5);

        assertEquals(1, u.getStatus());
        assertNull(u.getLockedUntil());
    }

    @Test
    @DisplayName("登录成功 ⇒ 清零失败计数并清掉到期时间")
    void successClearsLockState() {
        SysUser u = user(1, OffsetDateTime.now().plusMinutes(5), 3);

        service.recordSuccess(u);

        assertEquals(0, u.getFailedLoginCount());
        assertNull(u.getLockedUntil());
    }
}
