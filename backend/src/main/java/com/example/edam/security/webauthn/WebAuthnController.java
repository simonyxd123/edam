package com.example.edam.security.webauthn;

import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import com.example.edam.exception.RateLimitExceededException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.WebAuthnCredentialRepository;
import com.example.edam.security.ClientIpResolver;
import com.example.edam.security.CurrentUserId;
import com.example.edam.security.LoginAttemptService;
import com.example.edam.security.LoginRateLimiter;
import com.example.edam.security.TotpUtil;
import com.example.edam.service.TokenIssuanceService;
import com.example.edam.util.TimeUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * WebAuthn Controller（v3.3 W-5.2 + W-5.3）
 *
 * 注册 + 登录端点
 */
@Slf4j
@RestController
@RequestMapping("/auth/webauthn")
@RequiredArgsConstructor
public class WebAuthnController {

    private static final SecureRandom RNG = new SecureRandom();

    private final WebAuthnProperties properties;
    private final WebAuthnChallengeStore challengeStore;
    private final WebAuthnCredentialRepository credentialRepository;
    private final WebAuthnRegistrationService registrationService;
    private final WebAuthnAuthenticationService authenticationService;
    private final SysUserRepository userRepository;
    private final LoginAttemptService loginAttemptService;
    private final TokenIssuanceService tokenIssuanceService;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;

    // ========================================================================
    // W-5.2 注册流程
    // ========================================================================

    /**
     * 步骤 1：开始注册 → 生成 challenge + 注册选项
     */
    @PostMapping("/register/begin")
    @AuditOp(op = AuditOpType.LOGIN, resourceType = "webauthn_credential",
      detail = "action=register_begin")
    public ResponseEntity<RegistrationChallengeResponse> beginRegistration(
            @RequestBody BeginRegistrationRequest request,
            @CurrentUserId Long userId) {

        SysUser user = userRepository.findById(userId);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        String challenge = challengeStore.generateChallenge();
        challengeStore.saveChallenge("register", user.getEmployeeNo(), challenge);

        RegistrationChallengeResponse response = new RegistrationChallengeResponse();
        response.setChallenge(challenge);
        response.setRpId(properties.getRpId());
        response.setRpName(properties.getRpName());
        response.setUserId(Base64.getUrlEncoder().withoutPadding()
            .encodeToString(user.getEmployeeNo().getBytes()));
        response.setUserName(user.getEmployeeNo());
        response.setUserDisplayName(user.getRealName());
        response.setTimeout(properties.getTimeout());
        response.setPubKeyCredParams(properties.getPubKeyCredParams().stream()
            .map(id -> new PubKeyCredParam(id, "public-key")).toList());

        // 已注册凭据列表（避免重复注册）
        List<WebAuthnCredential> existing = credentialRepository.findActiveByUserId(userId);
        response.setExcludeCredentials(existing.stream()
            .map(c -> new ExcludeCredential(c.getCredentialId())).toList());

        // 认证器选择
        response.setAuthenticatorSelection(new AuthenticatorSelection(
            properties.getAuthenticatorSelection().isResidentKey(),
            properties.getAuthenticatorSelection().getUserVerification(),
            properties.getAuthenticatorSelection().getAuthenticatorAttachment()
        ));

        response.setAttestation(properties.getAttestation());
        response.setUserVerification(properties.getUserVerification());

        log.info("webauthn_register_begin user_id={} employee_no={}",
            userId, user.getEmployeeNo());
        return ResponseEntity.ok(response);
    }

    /**
     * 步骤 2：完成注册 → 验证 attestation + 存储凭据
     */
    @PostMapping("/register/complete")
    @AuditOp(op = AuditOpType.CONFIG_CHANGE, resourceType = "webauthn_credential",
      detail = "action=register_complete")
    public ResponseEntity<RegistrationCompleteResponse> completeRegistration(
            @RequestBody CompleteRegistrationRequest request,
            @CurrentUserId Long userId) {

        SysUser user = userRepository.findById(userId);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        // 1. 验证 challenge
        if (!challengeStore.consumeChallenge("register", user.getEmployeeNo(), request.getChallenge())) {
            return ResponseEntity.badRequest()
                .body(new RegistrationCompleteResponse(false, "Challenge 已过期或无效"));
        }

        // 2. 验证 attestation + 解析公钥
        WebAuthnCredential credential;
        try {
            credential = registrationService.verifyAndCreateCredential(
                userId, request);
        } catch (Exception e) {
            log.error("webauthn_register_failed user_id={} error={}", userId, e.getMessage());
            return ResponseEntity.badRequest()
                .body(new RegistrationCompleteResponse(false, "Attestation 验证失败: " + e.getMessage()));
        }

        log.info("webauthn_register_success credential_id={} user_id={}",
            credential.getCredentialId(), userId);

        RegistrationCompleteResponse response = new RegistrationCompleteResponse(true, "注册成功");
        response.setCredentialId(credential.getCredentialId());
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // W-5.3 认证流程
    // ========================================================================

    /**
     * 步骤 1：开始认证 → 生成 challenge
     */
    @PostMapping("/login/begin")
    @AuditOp(op = AuditOpType.LOGIN, resourceType = "webauthn_credential",
      detail = "action=login_begin", hideParams = true)
    public ResponseEntity<LoginChallengeResponse> beginLogin(
            @RequestBody BeginLoginRequest request,
            HttpServletRequest httpRequest) {

        String employeeNo = request.getEmployeeNo();

        // 限流：与密码登录同一套策略（IP 5/min + 工号 3/min）。
        // WebAuthn 登录是匿名的，不限流就能被用来枚举工号
        // （allowCredentials 的有无会暴露"这个人注册过密钥没有"）。
        enforceLoginRateLimit(employeeNo, httpRequest);

        String challenge = challengeStore.generateChallenge();

        // 1. 获取该用户的已注册凭据
        SysUser user = userRepository.findByEmployeeNo(employeeNo);
        List<String> allowCredentials = new ArrayList<>();
        if (user != null) {
            allowCredentials = credentialRepository.findActiveByUserId(user.getId())
                .stream()
                .map(WebAuthnCredential::getCredentialId)
                .toList();
        }

        // 2. 保存 challenge
        challengeStore.saveChallenge("login", employeeNo, challenge);

        LoginChallengeResponse response = new LoginChallengeResponse();
        response.setChallenge(challenge);
        response.setRpId(properties.getRpId());
        response.setTimeout(properties.getTimeout());
        response.setAllowCredentials(allowCredentials);
        response.setUserVerification(properties.getUserVerification());

        log.info("webauthn_login_begin employee_no={} credential_count={}",
            employeeNo, allowCredentials.size());
        return ResponseEntity.ok(response);
    }

    /**
     * 步骤 2：完成认证 → 验证 assertion + 签发 JWT
     */
    @PostMapping("/login/complete")
    @AuditOp(op = AuditOpType.LOGIN, resourceType = "webauthn_credential",
      detail = "action=login_complete", hideParams = true)
    public ResponseEntity<LoginCompleteResponse> completeLogin(
            @RequestBody CompleteLoginRequest request,
            HttpServletRequest httpRequest) {

        String employeeNo = request.getEmployeeNo();

        // 0. 限流（与密码登录同一套策略）
        enforceLoginRateLimit(employeeNo, httpRequest);

        // 1. 验证 challenge
        if (!challengeStore.consumeChallenge("login", employeeNo, request.getChallenge())) {
            return ResponseEntity.badRequest()
                .body(new LoginCompleteResponse(false, "Challenge 已过期或无效", null, null));
        }

        // 2. 验证 assertion
        SysUser user;
        try {
            user = authenticationService.verifyAssertionAndLogin(
                employeeNo, request);
        } catch (Exception e) {
            log.error("webauthn_login_failed employee_no={} error={}",
                employeeNo, e.getMessage());
            return ResponseEntity.badRequest()
                .body(new LoginCompleteResponse(false, "Assertion 验证失败: " + e.getMessage(), null, null));
        }

        // 3. 账号状态检查（锁定 / 禁用）—— 与密码登录同一套判定。
        //    此前这条路径完全没查状态，已离职的账号照样能用安全密钥换到有效 JWT。
        try {
            loginAttemptService.assertLoginAllowed(user);
        } catch (RuntimeException e) {
            log.warn("webauthn_login_rejected employee_no={} reason={}",
                employeeNo, e.getMessage());
            return ResponseEntity.badRequest()
                .body(new LoginCompleteResponse(false, e.getMessage(), null, null));
        }

        // 4. MFA 校验 —— 用户启用了 MFA 时仍需 TOTP。
        //    WebAuthn 本身是"持有证明"，但它只证明"是这个认证器"，
        //    不替代系统策略里已启用的第二因子。
        if (user.getMfaEnabled() != null && user.getMfaEnabled() == 1) {
            String mfaCode = request.getMfaCode();
            if (mfaCode == null || mfaCode.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(new LoginCompleteResponse(false, "MFA 验证码必填", null, null));
            }
            if (!TotpUtil.verify(user.getMfaSecret(), mfaCode)) {
                loginAttemptService.recordFailure(user, LoginAttemptService.MAX_FAILED_ATTEMPTS);
                return ResponseEntity.badRequest()
                    .body(new LoginCompleteResponse(false, "MFA 验证码错误", null, null));
            }
        }

        // 5. 签发 JWT —— 角色与权限码一律从 RBAC 表读。
        //    此前这里写死了 ROLE_EMPLOYEE + 三条固定权限码，
        //    等于 WebAuthn 登录完全绕过 RBAC（管理员会被降级成普通员工，
        //    反过来没角色的用户反倒拿到了那三条权限）。
        TokenIssuanceService.IssuedTokens tokens = tokenIssuanceService.issue(user);

        loginAttemptService.recordSuccess(user);

        log.info("webauthn_login_success user_id={} employee_no={} roles={}",
            user.getId(), employeeNo, tokens.getRoleCodes());

        return ResponseEntity.ok(new LoginCompleteResponse(
            true, "登录成功", tokens.getAccessToken(), tokens.getRefreshToken()));
    }

    // ========================================================================
    // W-5.4 凭据管理
    // ========================================================================

    /**
     * 列出当前用户所有 WebAuthn 凭据
     */
    @GetMapping("/credentials")
    @AuditOp(op = AuditOpType.LIST, resourceType = "webauthn_credential")
    public ResponseEntity<List<CredentialInfo>> listCredentials(
            @CurrentUserId Long userId) {
        List<WebAuthnCredential> creds = credentialRepository.findActiveByUserId(userId);
        List<CredentialInfo> result = creds.stream()
            .map(c -> {
                CredentialInfo info = new CredentialInfo();
                info.setId(c.getId());
                info.setCredentialId(c.getCredentialId());
                info.setName(c.getName());
                info.setCredentialType(c.getCredentialType());
                info.setUserVerification(c.getUserVerification());
                info.setLastUsedAt(c.getLastUsedAt());
                info.setCreatedAt(c.getCreatedAt());
                return info;
            })
            .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 撤销指定凭据
     */
    @DeleteMapping("/credentials/{credentialId}")
    @AuditOp(op = AuditOpType.DELETE, resourceType = "webauthn_credential",
      detail = "credential_id=#credentialId")
    public ResponseEntity<Void> revokeCredential(
            @PathVariable String credentialId,
            @CurrentUserId Long userId) {
        Optional<WebAuthnCredential> opt = credentialRepository.findByCredentialId(credentialId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        credentialRepository.revoke(opt.get().getId(),
            TimeUtil.nowUtcLocalDateTime(), "用户主动撤销");
        log.info("webauthn_credential_revoked credential_id={} user_id={}",
            credentialId, userId);
        return ResponseEntity.noContent().build();
    }

    // ========================================================================
    // DTO 类
    // ========================================================================

    /**
     * 登录限流 —— 与密码登录共用 {@link LoginRateLimiter}（IP 5/min + 工号 3/min）。
     *
     * <p>WebAuthn 登录端点是匿名的，不限流就能被用来：
     * <ul>
     *   <li>刷 challenge 撑大 Redis</li>
     *   <li>枚举工号 —— {@code allowCredentials} 的有无会暴露"这个人注册过密钥没有"</li>
     * </ul>
     */
    private void enforceLoginRateLimit(String employeeNo, HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        LoginRateLimiter.RateLimitResult result = loginRateLimiter.check(clientIp, employeeNo);
        if (!result.isAllowed()) {
            log.warn("webauthn_login_rate_limited employee_no={} client_ip={} dimension={}",
                employeeNo, clientIp, result.getDeniedDimension());
            throw new RateLimitExceededException(
                result.getDeniedDimension(),
                result.getRetryAfterSeconds(),
                result.getRemainingTokens()
            );
        }
    }

    @Data
    public static class BeginRegistrationRequest {
        private String name;  // 凭据名称（可选）
    }

    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RegistrationChallengeResponse {
        private String challenge;
        private String rpId;
        private String rpName;
        private String userId;
        private String userName;
        private String userDisplayName;
        private long timeout;
        private List<PubKeyCredParam> pubKeyCredParams;
        private List<ExcludeCredential> excludeCredentials;
        private AuthenticatorSelection authenticatorSelection;
        private String attestation;
        private String userVerification;
    }

    @Data
    public static class PubKeyCredParam {
        private Long alg;  // -7=ES256, -257=RS256
        private String type;
        public PubKeyCredParam() {}
        public PubKeyCredParam(Long alg, String type) { this.alg = alg; this.type = type; }
    }

    @Data
    public static class ExcludeCredential {
        private String id;
        public ExcludeCredential() {}
        public ExcludeCredential(String id) { this.id = id; }
    }

    @Data
    public static class AuthenticatorSelection {
        private boolean requireResidentKey;
        private String userVerification;
        private String authenticatorAttachment;
        public AuthenticatorSelection() {}
        public AuthenticatorSelection(boolean rrk, String uv, String aa) {
            this.requireResidentKey = rrk;
            this.userVerification = uv;
            this.authenticatorAttachment = aa;
        }
    }

    @Data
    public static class CompleteRegistrationRequest {
        private String challenge;
        private String credentialId;     // credentialIdId（base64url）
        private String attestation;       // 客户端返回的 attestation 对象（CBOR → JSON）
        private String clientDataJSON;
        private String name;
    }

    @Data
    public static class RegistrationCompleteResponse {
        private boolean success;
        private String message;
        private String credentialId;
        public RegistrationCompleteResponse() {}
        public RegistrationCompleteResponse(boolean s, String m) { this.success = s; this.message = m; }
    }

    @Data
    public static class BeginLoginRequest {
        private String employeeNo;
    }

    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LoginChallengeResponse {
        private String challenge;
        private String rpId;
        private long timeout;
        private List<String> allowCredentials;
        private String userVerification;
    }

    @Data
    public static class CompleteLoginRequest {
        private String employeeNo;
        private String challenge;
        private String credentialId;
        private String authenticatorData;  // CBOR 编码的 authenticatorData
        private String clientDataJSON;
        private String signature;
        /** 启用了 MFA 的账号必须带上 TOTP 验证码 */
        private String mfaCode;
    }

    @Data
    public static class LoginCompleteResponse {
        private boolean success;
        private String message;
        private String accessToken;
        private String refreshToken;
        public LoginCompleteResponse() {}
        public LoginCompleteResponse(boolean s, String m, String at, String rt) {
            this.success = s; this.message = m; this.accessToken = at; this.refreshToken = rt;
        }
    }

    @Data
    public static class CredentialInfo {
        private Long id;
        private String credentialId;
        private String name;
        private String credentialType;
        private String userVerification;
        private LocalDateTime lastUsedAt;
        private LocalDateTime createdAt;
    }
}