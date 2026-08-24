package com.example.edam.security.webauthn;

import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.WebAuthnCredentialRepository;
import com.example.edam.security.JwtTokenProvider;
import com.fasterxml.jackson.annotation.JsonInclude;
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
    private final JwtTokenProvider jwtTokenProvider;

    // ========================================================================
    // W-5.2 注册流程
    // ========================================================================

    /**
     * 步骤 1：开始注册 → 生成 challenge + 注册选项
     */
    @PostMapping("/register/begin")
    public ResponseEntity<RegistrationChallengeResponse> beginRegistration(
            @RequestBody BeginRegistrationRequest request,
            @RequestHeader("X-User-Id") Long userId) {

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
    public ResponseEntity<RegistrationCompleteResponse> completeRegistration(
            @RequestBody CompleteRegistrationRequest request,
            @RequestHeader("X-User-Id") Long userId) {

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
    public ResponseEntity<LoginChallengeResponse> beginLogin(
            @RequestBody BeginLoginRequest request) {

        String employeeNo = request.getEmployeeNo();
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
    public ResponseEntity<LoginCompleteResponse> completeLogin(
            @RequestBody CompleteLoginRequest request) {

        String employeeNo = request.getEmployeeNo();

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

        // 3. 签发 JWT Token
        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.createAccessToken(
            user.getId(), sessionId, List.of("ROLE_EMPLOYEE"));
        String refreshToken = jwtTokenProvider.createRefreshToken();

        log.info("webauthn_login_success user_id={} employee_no={}",
            user.getId(), employeeNo);

        return ResponseEntity.ok(new LoginCompleteResponse(
            true, "登录成功", accessToken, refreshToken));
    }

    // ========================================================================
    // W-5.4 凭据管理
    // ========================================================================

    /**
     * 列出当前用户所有 WebAuthn 凭据
     */
    @GetMapping("/credentials")
    public ResponseEntity<List<CredentialInfo>> listCredentials(
            @RequestHeader("X-User-Id") Long userId) {
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
    public ResponseEntity<Void> revokeCredential(
            @PathVariable String credentialId,
            @RequestHeader("X-User-Id") Long userId) {
        Optional<WebAuthnCredential> opt = credentialRepository.findByCredentialId(credentialId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        credentialRepository.revoke(opt.get().getId(),
            LocalDateTime.now(), "用户主动撤销");
        log.info("webauthn_credential_revoked credential_id={} user_id={}",
            credentialId, userId);
        return ResponseEntity.noContent().build();
    }

    // ========================================================================
    // DTO 类
    // ========================================================================

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