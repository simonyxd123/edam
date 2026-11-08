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
import java.util.Set;
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

    /** 单个 refresh_token 的 Redis key 前缀。 */
    private static final String REFRESH_KEY_PREFIX = "refresh:";

    /**
     * 「用户 → 其名下所有 refresh_token」的 Redis 集合索引前缀。
     *
     * <p>为什么需要这个索引（2026-11-07 补）：{@code /users/{id}/revoke-keys}
     * 承诺"立即吊销该用户所有密钥"，但 token 只以 {@code refresh:<token>} → userId
     * 的形式存在，**没法按用户反查**。没有索引就只能 {@code KEYS refresh:*}
     * 全库扫描 —— 那在生产上是会阻塞 Redis 的操作。
     * 有索引之后，吊销就是"取出集合里的 token 逐个删"，O(该用户的 token 数)。
     */
    private static final String USER_TOKEN_INDEX_PREFIX = "refresh:user:";

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

        storeRefreshToken(user.getId(), refreshToken);

        log.info("token_issued user_id={} session_id={} roles={} permission_count={}",
            user.getId(), sessionId, roleCodes, permissionCodes.size());

        return new IssuedTokens(accessToken, refreshToken, sessionId, roleCodes);
    }

    /**
     * 轮换 refresh_token：旧的立即作废，发一张新的。
     *
     * <p>用于 {@code /auth/refresh}。
     *
     * <p><b>为什么要轮换</b>（2026-11-07 补）：原先这里只换 access_token，
     * refresh_token 原封不动继续用 —— 一张泄露的 refresh_token 就能在
     * 整个 TTL（7 天）里无限续期，**没有重放检测、没有失效点**。
     * 轮换之后，旧 token 一用就作废；新 token 只在响应里返回一次。
     *
     * <p>前端已经兼容：{@code web/src/api/client.ts} 在刷新响应里带了
     * {@code refresh_token} 时会写回 localStorage。
     */
    public IssuedTokens refresh(SysUser user, String oldRefreshToken) {
        List<String> roleCodes = resolveRoleCodes(user.getId());
        List<String> permissionCodes = new ArrayList<>(
            permissionService.getUserPermissionCodes(user.getId()));

        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.createAccessToken(
            user.getId(), sessionId, roleCodes, permissionCodes);

        // 旧的立即作废（如果调用方没给旧的，就只是补发一张新的）
        if (oldRefreshToken != null && !oldRefreshToken.isBlank()) {
            redisTemplate.delete(REFRESH_KEY_PREFIX + oldRefreshToken);
            redisTemplate.opsForSet().remove(
                USER_TOKEN_INDEX_PREFIX + user.getId(), oldRefreshToken);
        }
        String newRefreshToken = jwtTokenProvider.createRefreshToken();
        storeRefreshToken(user.getId(), newRefreshToken);

        log.info("token_refreshed user_id={} session_id={} roles={} rotated=true",
            user.getId(), sessionId, roleCodes);

        return new IssuedTokens(accessToken, newRefreshToken, sessionId, roleCodes);
    }

    /**
     * 吊销该用户**全部** refresh_token（紧急吊销）。
     *
     * <p>调用方应同时把 {@code sys_session.revoked} 置 1（DB 侧留痕）。
     *
     * <p><b>能吊销什么、不能吊销什么</b>：refresh_token 是**可续期的凭证** ——
     * 吊销它之后，被吊销的用户再也换不到新的 access_token，这一刻就断了。
     * 已经签发出去的那张 access_token 是无状态 JWT，服务端没有黑名单，
     * 它会活到自己的 TTL（默认 10 分钟）为止。这是 JWT 无状态的固有代价，
     * 管理员手册里已经按这个口径写（不再承诺"立即"）。
     *
     * @return 被吊销的 refresh_token 数量
     */
    public int revokeAllForUser(Long userId) {
        String indexKey = USER_TOKEN_INDEX_PREFIX + userId;
        Set<String> tokens = redisTemplate.opsForSet().members(indexKey);
        int revoked = 0;
        if (tokens != null && !tokens.isEmpty()) {
            for (String token : tokens) {
                if (Boolean.TRUE.equals(redisTemplate.delete(REFRESH_KEY_PREFIX + token))) {
                    revoked++;
                }
            }
        }
        redisTemplate.delete(indexKey);
        log.info("user_tokens_revoked user_id={} revoked_refresh_tokens={}", userId, revoked);
        return revoked;
    }

    /** 把 refresh_token 写进 Redis，并登记到该用户的 token 索引。 */
    private void storeRefreshToken(Long userId, String refreshToken) {
        Duration ttl = Duration.ofSeconds(jwtTokenProvider.getRefreshTokenTtlSeconds());
        redisTemplate.opsForValue().set(
            REFRESH_KEY_PREFIX + refreshToken, String.valueOf(userId), ttl);

        String indexKey = USER_TOKEN_INDEX_PREFIX + userId;
        redisTemplate.opsForSet().add(indexKey, refreshToken);
        // 索引的 TTL 要 ≥ 集合里最长寿命的 token —— 每次签发都顺手续期即可
        redisTemplate.expire(indexKey, ttl);
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
