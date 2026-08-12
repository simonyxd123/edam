package com.example.edam.service;

import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 鉴权服务
 * - 登录（密码校验 + 签发 JWT）
 * - 刷新 Token
 * - 登出（撤销 refresh_token）
 * - 限流（Redis 计数器）
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

    /**
     * 登录
     */
    @Transactional
    public Map<String, Object> login(String employeeNo, String password) {
        // 1. 限流检查（IP + 工号）
        checkRateLimit(employeeNo);

        // 2. 查询用户
        SysUser user = userRepository.findByEmployeeNo(employeeNo);
        if (user == null) {
            throw new ResourceNotFoundException("工号或密码错误");
        }

        // 3. 检查账号状态
        if (user.getStatus() == 3) {
            throw new IllegalArgumentException("账号已锁定，请 30 分钟后再试");
        }
        if (user.getStatus() == 2) {
            throw new IllegalArgumentException("账号已禁用，请联系管理员");
        }

        // 4. 密码校验
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            handleFailedLogin(user);
            throw new IllegalArgumentException("工号或密码错误");
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

        return Map.of(
            "access_token", accessToken,
            "refresh_token", refreshToken,
            "token_type", "Bearer",
            "expires_in", 600
        );
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
        return Map.of(
            "access_token", newAccessToken,
            "token_type", "Bearer",
            "expires_in", 600
        );
    }

    /**
     * 登出（撤销 refresh_token）
     */
    public void logout(String refreshToken) {
        redisTemplate.delete("refresh:" + refreshToken);
    }

    /**
     * 限流检查
     */
    private void checkRateLimit(String employeeNo) {
        String key = "ratelimit:login:" + employeeNo;
        String count = redisTemplate.opsForValue().get(key);
        if (count != null && Integer.parseInt(count) >= 3) {
            throw new IllegalArgumentException("登录过于频繁，请稍后再试");
        }
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, Duration.ofMinutes(1));
    }

    /**
     * 处理登录失败
     */
    private void handleFailedLogin(SysUser user) {
        int failedCount = user.getFailedLoginCount() + 1;
        user.setFailedLoginCount(failedCount);
        if (failedCount >= MAX_FAILED_ATTEMPTS) {
            user.setStatus(3); // 锁定
            log.warn("账号锁定: employee_no={}", user.getEmployeeNo());
        }
        userRepository.updateById(user);
    }
}