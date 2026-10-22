package com.example.edam.service;

import com.example.edam.exception.AccountLockedException;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.JwtTokenProvider;
import com.example.edam.security.LoginAttemptService;
import com.example.edam.security.TotpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * v3.2 V-1 RBAC：login/refresh 从 DB 读真实角色；getCurrentUser 返回 roles+permissions
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
    private final PermissionService permissionService;
    private final UserRoleService userRoleService;
    private final LoginAttemptService loginAttemptService;
    private final PasswordService passwordService;

    /**
     * 自助修改个人资料。
     *
     * <p>只允许改 email / phone / real_name —— 工号、角色、状态这些
     * 敏感字段不开放给本人修改。
     */
    @Transactional
    public void updateProfile(Long userId, String email, String phone, String realName) {
        SysUser user = userRepository.findById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("用户不存在");
        }
        if (email != null && !email.isBlank()) {
            user.setEmail(email.trim());
        }
        if (phone != null) {
            user.setPhone(phone.trim());
        }
        if (realName != null && !realName.isBlank()) {
            user.setRealName(realName.trim());
        }
        userRepository.updateById(user);
        log.info("user_profile_updated, user_id={}", userId);
    }

    /**
     * 自助修改密码。
     *
     * <p>委托给 {@link PasswordService} —— 那里的实现更完整：
     * 密码策略 + **历史密码不可重用**（sys_password_history）+ 记录历史。
     * 本项目此前 PasswordService 写好了却**从未被任何 controller 调用**，
     * 等保要求的"历史密码不可重用"完全没生效。
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isBlank()) {
            throw new IllegalArgumentException("旧密码不能为空");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        // 旧密码错误要计入失败次数（独立事务，不会被这里的异常回滚）
        SysUser user = userRepository.findById(userId);
        if (user != null && !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            loginAttemptService.recordFailure(user, MAX_FAILED_ATTEMPTS);
        }
        passwordService.changePassword(userId, oldPassword, newPassword);
    }

    /**
     * 登录
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
            // 失败计数必须走**独立事务**：本方法带 @Transactional，
            // 后面 throw 的 RuntimeException 会把同事务里的 updateById 一起回滚，
            // 导致失败计数永远写不进 DB —— 暴力破解防护形同虚设
            loginAttemptService.recordFailure(user, MAX_FAILED_ATTEMPTS);
            throw new ResourceNotFoundException("工号或密码错误");
        }

        // 4. MFA 校验（L3+ 资源 + 用户启用 MFA）
        if (user.getMfaEnabled() != null && user.getMfaEnabled() == 1) {
            if (mfaCode == null || mfaCode.isBlank()) {
                loginAttemptService.recordFailure(user, MAX_FAILED_ATTEMPTS);
                throw new IllegalArgumentException("MFA 验证码必填");
            }
            // 真正比对 mfaSecret（RFC 6238 TOTP）。
            // 原来这里只校验"是不是 6 位数字"，任何 6 位数字都能通过 ——
            // 等保要求的双因素认证等于没有。
            if (!TotpUtil.verify(user.getMfaSecret(), mfaCode)) {
                loginAttemptService.recordFailure(user, MAX_FAILED_ATTEMPTS);
                throw new IllegalArgumentException("MFA 验证码错误");
            }
        }

        // 5. 重置失败计数（独立事务，避免与后面的异常同生共死）
        loginAttemptService.recordSuccess(user);

        // 6. 签发 token（角色 + 权限码 都写进 claims，让 @PreAuthorize 能校验）
        String sessionId = UUID.randomUUID().toString();
        List<String> roleCodes = new ArrayList<>(userRoleService.getRoleCodes(user.getId()));
        if (roleCodes.isEmpty()) {
            roleCodes.add("employee");  // 兜底
        }
        List<String> permissionCodes = new ArrayList<>(
            permissionService.getUserPermissionCodes(user.getId()));
        String accessToken = jwtTokenProvider.createAccessToken(
            user.getId(), sessionId, roleCodes, permissionCodes);
        String refreshToken = jwtTokenProvider.createRefreshToken();

        // 7. 存储 refresh_token 到 Redis
        redisTemplate.opsForValue().set(
            "refresh:" + refreshToken,
            String.valueOf(user.getId()),
            Duration.ofDays(7)
        );

        log.info("用户登录成功: employee_no={}, session_id={}, roles={}",
            employeeNo, sessionId, roleCodes);

        // 审计日志在 Controller 层调 auditHelper.logAudit 写（带真实 IP）

        Map<String, Object> response = new HashMap<>();
        response.put("access_token", accessToken);
        response.put("refresh_token", refreshToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", 600);
        response.put("user_id", user.getId());  // v3.2 V-1 RBAC：审计日志需要 user_id
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

        // 从 DB 读真实角色 + 权限码，写进 token
        List<String> roleCodes = new ArrayList<>(userRoleService.getRoleCodes(user.getId()));
        if (roleCodes.isEmpty()) {
            roleCodes.add("employee");
        }
        List<String> permissionCodes = new ArrayList<>(
            permissionService.getUserPermissionCodes(user.getId()));
        String newAccessToken = jwtTokenProvider.createAccessToken(
            user.getId(), UUID.randomUUID().toString(), roleCodes, permissionCodes);

        Map<String, Object> response = new HashMap<>();
        response.put("access_token", newAccessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", 600);
        return response;
    }

    /**
     * 登出（撤销 refresh_token）
     * 审计日志在 Controller 层调 auditHelper.logAudit 写（带真实 IP）
     */
    public void logout(String refreshToken) {
        if (refreshToken != null) {
            redisTemplate.delete("refresh:" + refreshToken);
        }
    }

    /**
     * 获取当前登录用户信息（含 roles + permissions 供前端 CurrentUser）
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
        // v3.2 V-1 RBAC：返回角色和权限供前端用
        response.put("roles", userRoleService.getRoleCodes(userId));
        response.put("permissions", permissionService.getUserPermissionCodes(userId));
        return response;
    }

    /**
     * 处理登录失败 —— 已移到 {@link com.example.edam.security.LoginAttemptService}。
     *
     * <p>原因：原先在 {@code @Transactional login()} 内直接 updateById，
     * 随后的 throw 会把这次写入一起回滚，失败计数永远落不了库。
     */
}
