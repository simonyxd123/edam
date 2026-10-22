package com.example.edam.controller;

import com.example.edam.security.sso.SsoStateStore;
import com.example.edam.exception.RateLimitExceededException;
import com.example.edam.security.ClientIpResolver;
import com.example.edam.security.LoginRateLimiter;
import com.example.edam.service.AuthService;
import com.example.edam.util.AuditHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 鉴权 Controller
 * 对应 openapi.yaml tag: auth
 *
 * v3.2 V-3：登录限流已集成（IP 5/min + 工号 3/min）
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "auth", description = "鉴权与会话")
public class AuthController {

    private final AuthService authService;
    private final SsoStateStore ssoStateStore;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final AuditHelper auditHelper;

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "工号 + 密码登录。限流策略：IP 5/min、工号 3/min；连续 5 次密码错误锁定 30 分钟")
    @SecurityRequirements
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = clientIpResolver.resolve(httpRequest);

        // Step 1：限流检查（IP + 工号双维度）
        LoginRateLimiter.RateLimitResult result = loginRateLimiter.check(clientIp, request.getEmployeeNo());
        if (!result.isAllowed()) {
            log.warn("login_rate_limited employee_no={} client_ip={} dimension={}",
                request.getEmployeeNo(), clientIp, result.getDeniedDimension());
            throw new RateLimitExceededException(
                result.getDeniedDimension(),
                result.getRetryAfterSeconds(),
                result.getRemainingTokens()
            );
        }

        // Step 2：调用业务（密码错误由 AuthService 内部累计 + 锁定）
        // 用 try-catch 捕获 RuntimeException，失败时写 audit（result="failure"）
        Map<String, Object> response;
        try {
            response = authService.login(
                request.getEmployeeNo(),
                request.getPassword(),
                request.getMfaCode()
            );
        } catch (RuntimeException e) {
            // 写登录失败审计（userId 未知，detail 保留 employee_no + 失败原因）
            auditHelper.logAudit(
                null, "login", "auth", null, "failure",
                "employee_no=" + request.getEmployeeNo() + ", reason=" + e.getMessage(),
                httpRequest);
            throw e;
        }

        // 写成功审计
        Long userId = ((Number) response.get("user_id")).longValue();
        auditHelper.logAudit(
            userId, "login", "auth", null, "success",
            "employee_no=" + request.getEmployeeNo(), httpRequest);

        return ResponseEntity.ok()
            .header("X-RateLimit-Remaining", String.valueOf(result.getRemainingTokens()))
            .body(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新 access_token")
    @SecurityRequirements
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody RefreshRequest request) {
        Map<String, Object> response = authService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @Operation(summary = "登出")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) LogoutRequest request,
            HttpServletRequest httpRequest) {
        if (request != null && request.getRefreshToken() != null) {
            authService.logout(request.getRefreshToken());
        }
        // 写审计日志：从 body 取 user_id（前端在 user store 拿到 user_id 后塞进 body）
        Long userId = request != null ? request.getUserId() : null;
        if (userId != null) {
            auditHelper.logAudit(userId, "logout", "auth", null, "success",
                "refresh_token cleared", httpRequest);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息")
    public ResponseEntity<Map<String, Object>> me() {
        Map<String, Object> response = authService.getCurrentUser();
        return ResponseEntity.ok(response);
    }

    /**
     * 自助修改个人资料（邮箱 / 手机 / 真实姓名）。
     *
     * <p>此前前端「设置」页要调这个端点，但后端**从来没有实现**，
     * 页面点一下就 404。
     */
    @PutMapping("/profile")
    @Operation(summary = "修改当前用户个人资料")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestBody ProfileRequest request,
            @com.example.edam.security.CurrentUserId Long userId) {
        authService.updateProfile(userId, request.getEmail(), request.getPhone(), request.getRealName());
        return ResponseEntity.ok(authService.getCurrentUser());
    }

    /**
     * 自助修改密码。
     *
     * <p>此前只有管理员重置他人密码的接口，**用户自己改密码的入口不存在** ——
     * 等保要求的"用户可自主修改密码"和"历史密码不可重用"都无从谈起。
     */
    @PostMapping("/change-password")
    @Operation(summary = "自助修改密码")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody ChangePasswordRequest request,
            @com.example.edam.security.CurrentUserId Long userId) {
        authService.changePassword(userId, request.getOldPassword(), request.getNewPassword());
        return ResponseEntity.ok(Map.of("changed", true));
    }

    @Data
    public static class ProfileRequest {
        private String email;
        private String phone;
        @com.fasterxml.jackson.annotation.JsonProperty("real_name")
        private String realName;
    }

    @Data
    public static class ChangePasswordRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("old_password")
        private String oldPassword;
        @com.fasterxml.jackson.annotation.JsonProperty("new_password")
        private String newPassword;
    }

    /**
     * SSO 一次性登录码 → token 交换
     *
     * <p>SSO 回调成功后重定向只带一个一次性 code（不带 token），
     * 前端用这个端点换 token。避免 token 出现在浏览器历史 /
     * 反代 access log / 下游 Referer 里。
     */
    @PostMapping("/sso/exchange")
    @Operation(summary = "SSO 一次性登录码换 token")
    public ResponseEntity<Map<String, Object>> ssoExchange(
            @RequestBody SsoExchangeRequest request) {
        Map<String, String> tokens = ssoStateStore.consumeLoginCode(request.getCode());
        Map<String, Object> response = new HashMap<>();
        response.put("access_token", tokens.get("access_token"));
        response.put("refresh_token", tokens.get("refresh_token"));
        response.put("token_type", "Bearer");
        response.put("expires_in", 600);
        return ResponseEntity.ok(response);
    }

    @Data
    public static class SsoExchangeRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("code")
        private String code;
    }

    @Data
    public static class LoginRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("employee_no")
        private String employeeNo;
        @com.fasterxml.jackson.annotation.JsonProperty("password")
        private String password;
        @com.fasterxml.jackson.annotation.JsonProperty("mfa_code")
        private String mfaCode;
    }

    @Data
    public static class RefreshRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("refresh_token")
        private String refreshToken;
    }

    @Data
    public static class LogoutRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("refresh_token")
        private String refreshToken;

        @com.fasterxml.jackson.annotation.JsonProperty("user_id")
        private Long userId;
    }
}