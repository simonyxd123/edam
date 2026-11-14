package com.example.edam.service;

import com.example.edam.model.SysUser;
import com.example.edam.repository.SysPasswordHistoryRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PasswordService} 的单测。
 *
 * <p>盯的是 2026-11-14 第六轮修的那条：<b>管理员重置密码后，被自动锁定的账号
 * 依然登不进去</b> —— 管理员看到"重置成功"，用户却还是进不去。
 */
class PasswordServiceTest {

    private SysUserRepository userRepository;
    private PasswordService service;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        userRepository = mock(SysUserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hashed");
        PasswordPolicy policy = mock(PasswordPolicy.class);
        service = new PasswordService(
            userRepository,
            mock(SysPasswordHistoryRepository.class),
            encoder,
            policy);
    }

    private SysUser locked(Long lockedUntilOffsetMinutes) {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setEmployeeNo("E000042");
        user.setStatus(3);   // locked
        user.setFailedLoginCount(5);
        if (lockedUntilOffsetMinutes != null) {
            user.setLockedUntil(OffsetDateTime.now().plusMinutes(lockedUntilOffsetMinutes));
        }
        return user;
    }

    @Test
    @DisplayName("重置密码 ⇒ 自动锁定的账号被解锁，失败计数归零")
    void resetPasswordUnlocksAutoLockedAccount() {
        SysUser user = locked(30L);
        when(userRepository.findById(USER_ID)).thenReturn(user);

        service.resetPassword(USER_ID, 1L);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).updateById(captor.capture());
        SysUser saved = captor.getValue();
        assertEquals(1, saved.getStatus(), "自动锁定的账号应被解锁");
        assertEquals(0, saved.getFailedLoginCount());
        assertNull(saved.getLockedUntil());
    }

    @Test
    @DisplayName("重置密码 ⇒ 管理员手动锁定的账号保持锁定")
    void resetPasswordKeepsManualLock() {
        // locked_until 为空 = 管理员手动锁定（第五轮的语义：不自动解锁）
        SysUser user = locked(null);
        when(userRepository.findById(USER_ID)).thenReturn(user);

        service.resetPassword(USER_ID, 1L);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).updateById(captor.capture());
        SysUser saved = captor.getValue();
        assertEquals(3, saved.getStatus(), "手动锁定不该被重置密码顺手解开");
        assertEquals(0, saved.getFailedLoginCount());
    }

    @Test
    @DisplayName("重置密码 ⇒ 禁用的账号保持禁用")
    void resetPasswordKeepsDisabledAccountDisabled() {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setStatus(2);   // disabled
        user.setFailedLoginCount(0);
        when(userRepository.findById(USER_ID)).thenReturn(user);

        service.resetPassword(USER_ID, 1L);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).updateById(captor.capture());
        assertEquals(2, captor.getValue().getStatus());
    }
}
