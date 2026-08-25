package com.example.edam.service;

import com.example.edam.exception.AccountLockedException;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 鉴权服务
 * - 登录（密码校验 + 签发 JWT）
 * - 刷新 Token
 * - 登出（撤销 refresh_token）
 * - 当前用户
 *
 * v3.2 V-3：限流已迁移到 LoginRateLimiter（IP + 工号双维度）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 30;

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final AuditService auditService;

    /**
     * 登录
     *
     * @param employeeNo 工号
     * @param password   密码（明文）
     * @param mfaCode    MFA 验证码（可选，L3+ 资源必填）
     * @return 登录响应（access_token + refresh_token）
     */
    @Transactional
    public Map<String, Object> login(String employeeNo, String password, String mfaCode) {
        // 1. 查询用户
        SysUser user = userRepository.findByEmployeeNo(employeeNo);
        if (user == null) {
            throw new ResourceNotFoundException("工号或密码错误");
        }

        // 2. 检查账号状态
        if (user.getStatus() == 3) {
            throw new AccountLockedException(LOCK_DURATION_MINUTES * 60L);
        }
        if (user.getStatus() == 2) {
            throw new IllegalArgumentException("账号已禁用，请联系管理员");
        }

        // 3. 密码校验
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            handleFailedLogin(user);
            throw new ResourceNotFoundException("工号或密码错误");
        }

        // 4. MFA 校验（L3+ 资源 + 用户启用 MFA）
        if (user.getMfaEnabled() != null && user.getMfaEnabled() == 1) {
            if (mfaCode == null || mfaCode.isBlank()) {
                throw new IllegalArgumentException("MFA 验证码必填");
            }
            // TODO: 接入 TOTP 校验（v3.2 二期）
            // 当前简单占位：长度校验
            if (mfaCode.length() != 6 || !mfaCode.matches("\\d{6}")) {
                throw new IllegalArgumentException("MFA 验证码格式错误（6 位数字）");
            }
        }

        // 5. 重置失败计数
        user.setFailedLoginCount(0);
        userRepository.updateById(user);

        // 6. 签发 token
        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.createAccessToken(
            user.getId(), sessionId, List.of("ROLE_USER"));
        String refreshToken = jwtTokenProvider.createRefreshToken();

        // 7. 存储 refresh_token 到 Redis
        redisTemplate.opsForValue().set(
            "refresh:" + refreshToken,
            String.valueOf(user.getId()),
            Duration.ofDays(7)
        );

        log.info("用户登录成功: employee_no={}, session_id={}", employeeNo, sessionId);

        // 写审计日志（异步）
        auditService.log(user.getId(), "login", "auth", null, "success");

        Map<String, Object> response = new HashMap<>();
        response.put("access_token", accessToken);
        response.put("refresh_token", refreshToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", 600);
        return response;
    }

    /**
     * 刷新 access_token
     */
    public Map<String, Object> refresh(String refreshToken) {
        String userId = redisTemplate.opsForValue().get("refresh:" + refreshToken);
        if (userId == null) {
            throw new IllegalArgumentException("refresh_token 无效或已过期");
        }
        SysUser user = userRepository.findById(Long.valueOf(userId));
        if (user == null) {
            throw new ResourceNotFoundException("用户不存在");
        }

        String newAccessToken = jwtTokenProvider.createAccessToken(
            user.getId(), UUID.randomUUID().toString(), List.of("ROLE_USER"));

        Map<String, Object> response = new HashMap<>();
        response.put("access_token", newAccessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", 600);
        return response;
    }

    /**
     * 登出（撤销 refresh_token）
     */
    public void logout(String refreshToken) {
        if (refreshToken != null) {
            redisTemplate.delete("refresh:" + refreshToken);
        }
    }

    /**
     * 获取当前登录用户信息
     */
    public Map<String, Object> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new IllegalArgumentException("未登录");
        }

        Long userId = Long.valueOf(auth.getName());
        SysUser user = userRepository.findById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("用户不存在");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("user_id", user.getId());
        response.put("employee_no", user.getEmployeeNo());
        response.put("real_name", user.getRealName());
        response.put("email", user.getEmail());
        response.put("dept_id", user.getDeptId());
        response.put("status", user.getStatus());
        response.put("last_login_at", user.getLastLoginAt());
        return response;
    }

    /**
     * 处理登录失败
     * - 累计失败次数
     * - 连续 5 次失败 → 锁定账号 30 分钟
     */
    private void handleFailedLogin(SysUser user) {
        int failedCount = user.getFailedLoginCount() + 1;
        user.setFailedLoginCount(failedCount);
        if (failedCount >= MAX_FAILED_ATTEMPTS) {
            user.setStatus(3); // 锁定
            log.warn("账号锁定: employee_no={} failed_count={}",
                user.getEmployeeNo(), failedCount);
        }
        userRepository.updateById(user);
    }
}