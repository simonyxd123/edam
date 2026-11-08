package com.example.edam.service;

import com.example.edam.exception.AccountLockedException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysSessionRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.JwtTokenProvider;
import com.example.edam.security.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService} 的令牌相关行为（2026-11-07 审计）。
 *
 * <p>盯两件事：
 * <ol>
 *   <li><b>refresh 要查账号状态</b> —— 禁用 / 锁定的账号不该还能续期。
 *       原先只查 token 在不在，于是管理员把离职员工禁用掉之后，
 *       他手上的 refresh_token 照样能换出新的 access_token。</li>
 *   <li><b>紧急吊销要真的作废 token</b> —— 原先 {@code revoke-keys}
 *       只打日志就返回 204，什么都没作废。</li>
 * </ol>
 */
class AuthServiceTokenTest {

    private static final String SECRET =
        "unit-test-secret-must-be-at-least-256-bits-long-for-hmac-sha256";
    private static final Long USER_ID = 9L;

    private SysUserRepository userRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private LoginAttemptService loginAttemptService;
    private TokenIssuanceService tokenIssuanceService;
    private SysSessionRepository sysSessionRepository;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(SysUserRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        loginAttemptService = mock(LoginAttemptService.class);
        tokenIssuanceService = mock(TokenIssuanceService.class);
        sysSessionRepository = mock(SysSessionRepository.class);

        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenTtlSeconds", 600L);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshTokenTtlSeconds", 604800L);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new AuthService(
            userRepository,
            mock(PasswordEncoder.class),
            redisTemplate,
            mock(AuditService.class),
            mock(PermissionService.class),
            mock(UserRoleService.class),
            loginAttemptService,
            mock(PasswordService.class),
            tokenIssuanceService,
            sysSessionRepository);
    }

    private SysUser activeUser() {
        SysUser u = new SysUser();
        u.setId(USER_ID);
        u.setEmployeeNo("E000009");
        u.setRealName("张三");
        u.setStatus(1);
        return u;
    }

    @Test
    @DisplayName("refresh 要查账号状态 —— 禁用 / 锁定的账号续不了期")
    void refreshChecksAccountStatus() {
        when(valueOps.get("refresh:rt")).thenReturn(String.valueOf(USER_ID));
        when(userRepository.findById(USER_ID)).thenReturn(activeUser());
        // 禁用 / 锁定 → assertLoginAllowed 抛异常
        doThrow(new AccountLockedException(60L))
            .when(loginAttemptService).assertLoginAllowed(any());

        assertThrows(AccountLockedException.class,
            () -> service.refresh("rt"));

        verify(tokenIssuanceService, never()).refresh(any(), anyString());
    }

    @Test
    @DisplayName("refresh 返回轮换后的新 refresh_token")
    void refreshReturnsRotatedToken() {
        when(valueOps.get("refresh:rt")).thenReturn(String.valueOf(USER_ID));
        when(userRepository.findById(USER_ID)).thenReturn(activeUser());

        TokenIssuanceService.IssuedTokens issued = new TokenIssuanceService.IssuedTokens(
            "new-access", "new-refresh", "sid", List.of("employee"));
        when(tokenIssuanceService.refresh(any(), eq("rt"))).thenReturn(issued);
        when(tokenIssuanceService.accessTokenTtlSeconds()).thenReturn(600L);

        Map<String, Object> resp = service.refresh("rt");

        assertEquals("new-access", resp.get("access_token"));
        // 客户端必须拿到新的 refresh_token —— 否则旧的那张下一轮就废了
        assertEquals("new-refresh", resp.get("refresh_token"));
        assertEquals(600L, resp.get("expires_in"));
        verify(loginAttemptService).assertLoginAllowed(any());
    }

    @Test
    @DisplayName("refresh 拿到不认识的 token ⇒ 400")
    void refreshWithUnknownTokenFails() {
        when(valueOps.get("refresh:bad")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.refresh("bad"));
    }

    @Test
    @DisplayName("紧急吊销：作废全部 refresh_token，并给 DB 会话留痕")
    void revokeAllForUserRevokesTokensAndSessions() {
        when(tokenIssuanceService.revokeAllForUser(USER_ID)).thenReturn(2);

        int revoked = service.revokeAllForUser(USER_ID, "离职");

        assertEquals(2, revoked);
        verify(tokenIssuanceService).revokeAllForUser(USER_ID);
        verify(sysSessionRepository).revokeAllByUserId(USER_ID, "离职");
    }

    @Test
    @DisplayName("DB 留痕失败也不能让吊销失败 —— Redis 那一步已经生效了")
    void revokeSurvivesSessionDbFailure() {
        when(tokenIssuanceService.revokeAllForUser(USER_ID)).thenReturn(1);
        when(sysSessionRepository.revokeAllByUserId(eq(USER_ID), anyString()))
            .thenThrow(new RuntimeException("db down"));

        int revoked = service.revokeAllForUser(USER_ID, "离职");

        assertEquals(1, revoked);
    }
}
