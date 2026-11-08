package com.example.edam.service;

import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
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

import java.util.HashMap;
import java.util.Map;

/**
 * 鉴权服务
 * - 登录（密码校验 + 签发 JWT）
 * - 刷新 Token
 * - 登出（撤销 refresh_token）
 * - 当前用户
 *
 * v3.2 V-1 RBAC：login/refresh 从 DB 读真实角色；getCurrentUser 返回 roles+permissions
 * v3.4.1：token 签发收敛到 {@link TokenIssuanceService}，与 WebAuthn / SSO 共用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = LoginAttemptService.MAX_FAILED_ATTEMPTS;

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final UserRoleService userRoleService;
    private final LoginAttemptService loginAttemptService;
    private final PasswordService passwordService;
    private final TokenIssuanceService tokenIssuanceService;
    private final com.example.edam.repository.SysSessionRepository sysSessionRepository;

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

        // 2. 检查账号状态（锁定 / 禁用）—— 三条登录路径共用同一套判定
        loginAttemptService.assertLoginAllowed(user);

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
        //    统一走 TokenIssuanceService —— WebAuthn / SSO 登录也走同一条路径，
        //    保证任何登录方式拿到的权限都来自 RBAC 表。
        TokenIssuanceService.IssuedTokens tokens = tokenIssuanceService.issue(user);

        log.info("用户登录成功: employee_no={}, session_id={}, roles={}",
            employeeNo, tokens.getSessionId(), tokens.getRoleCodes());

        // 审计日志由 Controller 层的 @AuditOp 注解 + 审计切面自动写
        // （成功/失败/拒绝三态齐全，且带真实 IP —— 手工 log 只能记到成功）

        Map<String, Object> response = new HashMap<>();
        response.put("access_token", tokens.getAccessToken());
        response.put("refresh_token", tokens.getRefreshToken());
        response.put("token_type", "Bearer");
        response.put("expires_in", tokenIssuanceService.accessTokenTtlSeconds());
        response.put("user_id", user.getId());  // v3.2 V-1 RBAC：审计日志需要 user_id
        return response;
    }

    /**
     * 刷新 access_token
     */
    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refresh_token 不能为空");
        }
        String userId = redisTemplate.opsForValue().get("refresh:" + refreshToken);
        if (userId == null) {
            throw new IllegalArgumentException("refresh_token 无效或已过期");
        }
        SysUser user = userRepository.findById(Long.valueOf(userId));
        if (user == null) {
            throw new ResourceNotFoundException("用户不存在");
        }

        // 账号状态：禁用 / 锁定的账号不该再续期。
        // 原先这里只查 token 存不存在 —— 管理员把离职员工禁用掉之后，
        // 他手上的 refresh_token 照样能换出新的 access_token，
        // "禁用"就只是个页面上的标记，管不住人。
        loginAttemptService.assertLoginAllowed(user);

        // 从 DB 读真实角色 + 权限码，写进 token；顺带**轮换** refresh_token
        // （旧的那张立刻作废，见 TokenIssuanceService#refresh）。
        TokenIssuanceService.IssuedTokens tokens =
            tokenIssuanceService.refresh(user, refreshToken);

        Map<String, Object> response = new HashMap<>();
        response.put("access_token", tokens.getAccessToken());
        // 轮换后的新 refresh_token 必须回给客户端，否则客户端手里的旧 token
        // 下一轮就废了 —— 前端 client.ts 已经会把它写回 localStorage。
        response.put("refresh_token", tokens.getRefreshToken());
        response.put("token_type", "Bearer");
        response.put("expires_in", tokenIssuanceService.accessTokenTtlSeconds());
        return response;
    }

    /**
     * 紧急吊销该用户的全部密钥（离职 / 违规）。
     *
     * <p>原实现是个**空壳** —— {@code /users/{id}/revoke-keys} 只打了一条日志
     * 就返回 204，token 一个都没作废。管理员在界面上点「紧急吊销」，
     * 弹个成功提示，然后**什么都没发生**。
     *
     * <p>现在做三件事：
     * <ol>
     *   <li>删掉该用户全部 refresh_token（Redis）—— 这一刻起他再也续不了期</li>
     *   <li>{@code sys_session.revoked = 1}（DB 留痕；会话表目前还没人写，
     *       但吊销路径先接上，将来会话落库就自动生效）</li>
     *   <li>写一条审计（{@code revoke_keys}）</li>
     * </ol>
     *
     * <p><b>已经签发出去的 access_token 不会立刻消失</b>：它是无状态 JWT，
     * 服务端没有黑名单，只能等它自己的 TTL（默认 10 分钟）走完。
     * 这是架构事实，不是实现偷懒 —— 管理员手册按这个口径写。
     */
    public int revokeAllForUser(Long userId, String reason) {
        int revoked = tokenIssuanceService.revokeAllForUser(userId);
        try {
            sysSessionRepository.revokeAllByUserId(userId, reason);
        } catch (Exception e) {
            // DB 侧留痕失败**不能**让吊销失败 —— Redis 那一步已经生效了，
            // token 已经续不了了；这里只是少了条会话留痕。
            log.warn("session_revoke_failed, user_id={}, error={}", userId, e.toString());
        }
        log.warn("user_keys_revoked user_id={} revoked_refresh_tokens={} reason={}",
            userId, revoked, reason);
        return revoked;
    }

    /**
     * 登出（撤销 refresh_token）
     * 审计日志由 Controller 层的 @AuditOp 注解 + 审计切面自动写
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
