package com.example.edam.security.webauthn;

import com.example.edam.exception.AccountLockedException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.WebAuthnCredentialRepository;
import com.example.edam.security.ClientIpResolver;
import com.example.edam.security.LoginAttemptService;
import com.example.edam.security.LoginRateLimiter;
import com.example.edam.service.TokenIssuanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WebAuthnController} 登录路径的单测。
 *
 * <p>盯的是三件事：
 * <ol>
 *   <li>签发的 token 权限**来自 RBAC**（走 {@link TokenIssuanceService}），
 *       而不是写死的 {@code ROLE_EMPLOYEE} + 三条固定权限码</li>
 *   <li>账号被锁定 / 禁用时**不能**签发 token</li>
 *   <li>启用了 MFA 的账号仍需 TOTP</li>
 * </ol>
 */
class WebAuthnControllerTest {

    private WebAuthnProperties properties;
    private WebAuthnChallengeStore challengeStore;
    private WebAuthnCredentialRepository credentialRepository;
    private WebAuthnRegistrationService registrationService;
    private WebAuthnAuthenticationService authenticationService;
    private SysUserRepository userRepository;
    private LoginAttemptService loginAttemptService;
    private TokenIssuanceService tokenIssuanceService;
    private LoginRateLimiter loginRateLimiter;
    private ClientIpResolver clientIpResolver;
    private WebAuthnController controller;

    @BeforeEach
    void setUp() {
        properties = new WebAuthnProperties();
        challengeStore = mock(WebAuthnChallengeStore.class);
        credentialRepository = mock(WebAuthnCredentialRepository.class);
        registrationService = mock(WebAuthnRegistrationService.class);
        authenticationService = mock(WebAuthnAuthenticationService.class);
        userRepository = mock(SysUserRepository.class);
        loginAttemptService = mock(LoginAttemptService.class);
        tokenIssuanceService = mock(TokenIssuanceService.class);
        loginRateLimiter = mock(LoginRateLimiter.class);
        clientIpResolver = mock(ClientIpResolver.class);

        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
        when(loginRateLimiter.check(any(), any()))
            .thenReturn(LoginRateLimiter.RateLimitResult.allowed(4));

        controller = new WebAuthnController(
            properties, challengeStore, credentialRepository, registrationService,
            authenticationService, userRepository, loginAttemptService, tokenIssuanceService,
            loginRateLimiter, clientIpResolver);
    }

    // ==================================================================
    // 授权：权限必须来自 RBAC
    // ==================================================================

    @Test
    @DisplayName("WebAuthn 登录的权限来自 RBAC 表，而不是写死的常量")
    void loginIssuesTokenFromRbac() {
        SysUser user = user(7L, 1, 0);
        stubChallengeOk();
        when(authenticationService.verifyAssertionAndLogin(eq("E000007"), any()))
            .thenReturn(user);
        when(tokenIssuanceService.issue(user))
            .thenReturn(new TokenIssuanceService.IssuedTokens(
                "AT", "RT", "session-1", List.of("dept_manager")));

        ResponseEntity<WebAuthnController.LoginCompleteResponse> resp =
            controller.completeLogin(request("E000007", null), null);

        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertEquals("AT", resp.getBody().getAccessToken());
        assertEquals("RT", resp.getBody().getRefreshToken());
        // 走的是统一签发逻辑 —— 那里才会去 RBAC 表读权限
        verify(tokenIssuanceService).issue(user);
    }

    // ==================================================================
    // 账号状态：锁定 / 禁用
    // ==================================================================

    @Test
    @DisplayName("账号被锁定时拒绝签发 token")
    void lockedAccountCannotLogin() {
        SysUser user = user(7L, 3, 0);
        stubChallengeOk();
        when(authenticationService.verifyAssertionAndLogin(eq("E000007"), any()))
            .thenReturn(user);
        doThrow(new AccountLockedException(1800L))
            .when(loginAttemptService).assertLoginAllowed(user);

        ResponseEntity<WebAuthnController.LoginCompleteResponse> resp =
            controller.completeLogin(request("E000007", null), null);

        assertEquals(400, resp.getStatusCode().value());
        assertNull(resp.getBody().getAccessToken());
        verify(tokenIssuanceService, never()).issue(any());
    }

    @Test
    @DisplayName("账号被禁用时拒绝签发 token")
    void disabledAccountCannotLogin() {
        SysUser user = user(7L, 2, 0);
        stubChallengeOk();
        when(authenticationService.verifyAssertionAndLogin(eq("E000007"), any()))
            .thenReturn(user);
        doThrow(new IllegalArgumentException("账号已禁用，请联系管理员"))
            .when(loginAttemptService).assertLoginAllowed(user);

        ResponseEntity<WebAuthnController.LoginCompleteResponse> resp =
            controller.completeLogin(request("E000007", null), null);

        assertEquals(400, resp.getStatusCode().value());
        assertNull(resp.getBody().getAccessToken());
        verify(tokenIssuanceService, never()).issue(any());
    }

    // ==================================================================
    // MFA
    // ==================================================================

    @Test
    @DisplayName("启用了 MFA 的账号必须带 TOTP 验证码")
    void mfaEnabledUserNeedsCode() {
        SysUser user = user(7L, 1, 1);
        stubChallengeOk();
        when(authenticationService.verifyAssertionAndLogin(eq("E000007"), any()))
            .thenReturn(user);

        ResponseEntity<WebAuthnController.LoginCompleteResponse> resp =
            controller.completeLogin(request("E000007", null), null);

        assertEquals(400, resp.getStatusCode().value());
        assertTrue(resp.getBody().getMessage().contains("MFA"));
        verify(tokenIssuanceService, never()).issue(any());
    }

    @Test
    @DisplayName("MFA 验证码错误时拒绝签发 token，并计入失败次数")
    void wrongMfaCodeRejected() {
        SysUser user = user(7L, 1, 1);
        user.setMfaSecret("JBSWY3DPEHPK3PXP");   // RFC 6238 测试密钥
        stubChallengeOk();
        when(authenticationService.verifyAssertionAndLogin(eq("E000007"), any()))
            .thenReturn(user);

        ResponseEntity<WebAuthnController.LoginCompleteResponse> resp =
            controller.completeLogin(request("E000007", "000000"), null);

        assertEquals(400, resp.getStatusCode().value());
        assertTrue(resp.getBody().getMessage().contains("MFA"));
        verify(tokenIssuanceService, never()).issue(any());
        verify(loginAttemptService).recordFailure(eq(user),
            eq(LoginAttemptService.MAX_FAILED_ATTEMPTS));
    }

    @Test
    @DisplayName("challenge 校验不过时不签发 token")
    void invalidChallengeRejected() {
        when(challengeStore.consumeChallenge(anyString(), anyString(), anyString()))
            .thenReturn(false);

        ResponseEntity<WebAuthnController.LoginCompleteResponse> resp =
            controller.completeLogin(request("E000007", null), null);

        assertEquals(400, resp.getStatusCode().value());
        assertNull(resp.getBody().getAccessToken());
        verify(tokenIssuanceService, never()).issue(any());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void stubChallengeOk() {
        when(challengeStore.consumeChallenge(eq("login"), eq("E000007"), eq("chal-1")))
            .thenReturn(true);
    }

    private WebAuthnController.CompleteLoginRequest request(String employeeNo, String mfaCode) {
        WebAuthnController.CompleteLoginRequest req = new WebAuthnController.CompleteLoginRequest();
        req.setEmployeeNo(employeeNo);
        req.setChallenge("chal-1");
        req.setCredentialId("cred-1");
        req.setMfaCode(mfaCode);
        return req;
    }

    private SysUser user(Long id, int status, int mfaEnabled) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setEmployeeNo("E000007");
        user.setStatus(status);
        user.setMfaEnabled(mfaEnabled);
        return user;
    }
}
