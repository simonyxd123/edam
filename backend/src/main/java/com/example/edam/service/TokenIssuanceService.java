package com.example.edam.service;

import com.example.edam.model.SysUser;
import com.example.edam.security.JwtTokenProvider;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Token 签发服务（v3.4.1）
 *
 * <p><b>为什么要有这个类</b>：access_token 的签发此前散落在四处，其中
 * **WebAuthn 登录**与 **SSO 登录**两处把角色和权限码写死了：
 *
 * <pre>
 * jwtTokenProvider.createAccessToken(user.getId(), sessionId,
 *     List.of("ROLE_EMPLOYEE"),
 *     List.of("dashboard:read", "video:read", "document:read"));
 * </pre>
 *
 * <p>JWT 里的 {@code permissions} 就是 {@code @PreAuthorize("hasAuthority(...)")}
 * 的**唯一**判据（见 {@code JwtAuthenticationFilter} 把 claims 转成
 * {@code SimpleGrantedAuthority}）—— 写死就等于这两条登录路径
 * **完全绕过了 RBAC**：
 * <ul>
 *   <li>管理员用 WebAuthn 登录 → 退化成只有 3 条读权限的"普通员工"</li>
 *   <li>反过来，一个在 RBAC 里**没有任何**权限的用户 → 反倒拿到了这 3 条</li>
 * </ul>
 *
 * <p>现在四个签发点（密码登录、刷新、WebAuthn 登录、SSO 登录）统一走这里，
 * 角色与权限码一律从 DB 读：
 * {@code sys_user_role} → {@code sys_role} → {@code sys_role_permission} → {@code sys_permission}。
 *
 * <p><b>注意</b>：权限码是**登录那一刻的快照**进 JWT 的 —— 改了角色不会
 * 立刻生效，必须让用户重新登录。这是 JWT 无状态的固有代价，
 * 管理员手册 §14.1 已就此提醒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenIssuanceService {

    /** 用户在 RBAC 里一个角色都没有时的兜底角色 code（与密码登录路径一致） */
    public static final String FALLBACK_ROLE = "employee";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRoleService userRoleService;
    private final PermissionService permissionService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 签发 access_token + refresh_token，并把 refresh_token 落到 Redis。
     *
     * <p>refresh_token **必须**落 Redis —— 不落的话 {@code /auth/refresh}
     * 拿到它必然报"无效或已过期"，用户登录一次后就再也续不了期。
     *
     * @param user 已通过身份校验的用户
     * @return 签发结果
     */
    public IssuedTokens issue(SysUser user) {
        List<String> roleCodes = resolveRoleCodes(user.getId());
        List<String> permissionCodes = new ArrayList<>(
            permissionService.getUserPermissionCodes(user.getId()));

        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.createAccessToken(
            user.getId(), sessionId, roleCodes, permissionCodes);
        String refreshToken = jwtTokenProvider.createRefreshToken();

        redisTemplate.opsForValue().set(
            "refresh:" + refreshToken,
            String.valueOf(user.getId()),
            Duration.ofSeconds(jwtTokenProvider.getRefreshTokenTtlSeconds()));

        log.info("token_issued user_id={} session_id={} roles={} permission_count={}",
            user.getId(), sessionId, roleCodes, permissionCodes.size());

        return new IssuedTokens(accessToken, refreshToken, sessionId, roleCodes);
    }

    /**
     * 只签发新的 access_token（**不**轮换 refresh_token，保持旧行为）。
     *
     * <p>用于 {@code /auth/refresh}：用户已持有有效 refresh_token，
     * 只需要换一张短期 access_token。
     */
    public IssuedTokens refresh(SysUser user) {
        List<String> roleCodes = resolveRoleCodes(user.getId());
        List<String> permissionCodes = new ArrayList<>(
            permissionService.getUserPermissionCodes(user.getId()));

        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.createAccessToken(
            user.getId(), sessionId, roleCodes, permissionCodes);

        log.info("token_refreshed user_id={} session_id={} roles={}",
            user.getId(), sessionId, roleCodes);

        return new IssuedTokens(accessToken, null, sessionId, roleCodes);
    }

    /**
     * 读该用户的角色 code 列表；一个角色都没有时兜底成 {@code employee}。
     */
    private List<String> resolveRoleCodes(Long userId) {
        List<String> roleCodes = new ArrayList<>(userRoleService.getRoleCodes(userId));
        if (roleCodes.isEmpty()) {
            roleCodes.add(FALLBACK_ROLE);
        }
        return roleCodes;
    }

    /**
     * access_token 有效期（秒）—— 供响应体 {@code expires_in} 使用。
     */
    public long accessTokenTtlSeconds() {
        return jwtTokenProvider.getAccessTokenTtlSeconds();
    }

    /**
     * 签发结果。
     */
    @Data
    public static class IssuedTokens {
        private String accessToken;
        private String refreshToken;
        private String sessionId;
        private List<String> roleCodes;

        public IssuedTokens() {}

        public IssuedTokens(String accessToken, String refreshToken,
                            String sessionId, List<String> roleCodes) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.sessionId = sessionId;
            this.roleCodes = roleCodes;
        }
    }
}
