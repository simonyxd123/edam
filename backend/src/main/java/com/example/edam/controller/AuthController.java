package com.example.edam.controller;

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