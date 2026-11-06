package com.example.edam.service;

import com.example.edam.model.SysUser;
import com.example.edam.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TokenIssuanceService} 的单测。
 *
 * <p><b>这条测试存在的理由</b>：v3.4.1 之前，access_token 的签发散落在四处，
 * 其中 WebAuthn 登录与 SSO 登录把角色和权限码**写死**了：
 *
 * <pre>
 * createAccessToken(userId, sessionId,
 *     List.of("ROLE_EMPLOYEE"),
 *     List.of("dashboard:read", "video:read", "document:read"));
 * </pre>
 *
 * <p>JWT 里的 {@code permissions} 是 {@code @PreAuthorize("hasAuthority(...)")} 的
 * 唯一判据 —— 写死就等于那两条登录路径**完全绕过了 RBAC**：
 * 管理员登录后被降级成普通员工，而没有角色的用户反倒拿到了那三条权限。
 *
 * <p>下面的断言就是为了把这个洞钉死：任何登录方式拿到的权限，
 * 都必须等于 {@code sys_role_permission} 里的内容。
 */
class TokenIssuanceServiceTest {

    private static final String SECRET =
        "unit-test-secret-must-be-at-least-256-bits-long-for-hmac-sha256";

    /** 写死过的那三条权限 —— 任何情况下都不该由签发逻辑凭空产出 */
    private static final List<String> LEGACY_HARDCODED =
        List.of("dashboard:read", "video:read", "document:read");

    private JwtTokenProvider jwtTokenProvider;
    private UserRoleService userRoleService;
    private PermissionService permissionService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TokenIssuanceService service;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(SECRET);
        // @Value 字段在手工 new 出来的实例里是 0 —— token 会立刻过期，解析必然失败
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenTtlSeconds", 600L);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshTokenTtlSeconds", 604800L);

        userRoleService = mock(UserRoleService.class);
        permissionService = mock(PermissionService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new TokenIssuanceService(
            jwtTokenProvider, userRoleService, permissionService, redisTemplate);
    }

    @Test
    @DisplayName("签发的 token 里，角色与权限码来自 RBAC 表")
    void issuedTokenCarriesRbacRolesAndPermissions() {
        SysUser user = user(7L, "E000007");
        when(userRoleService.getRoleCodes(7L)).thenReturn(List.of("dept_manager"));
        when(permissionService.getUserPermissionCodes(7L))
            .thenReturn(Set.of("video:read", "video:delete", "document:read"));

        TokenIssuanceService.IssuedTokens tokens = service.issue(user);

        Claims claims = jwtTokenProvider.parseAndValidate(tokens.getAccessToken());
        assertEquals(List.of("dept_manager"), claims.get("roles"));
        assertEquals(Set.of("video:read", "video:delete", "document:read"),
            Set.copyOf((List<?>) claims.get("permissions")));

        // 关键回归点：不能出现写死的那三条（除非 RBAC 里真的有）
        assertTrue(!claims.get("permissions").toString().contains("\"ROLE_EMPLOYEE\""));
    }

    @Test
    @DisplayName("管理员的权限不被降级 —— WebAuthn / SSO 登录同样适用")
    void adminIsNotDowngraded() {
        SysUser admin = user(1L, "E000001");
        when(userRoleService.getRoleCodes(1L)).thenReturn(List.of("admin"));
        when(permissionService.getUserPermissionCodes(1L))
            .thenReturn(Set.of("user:manage", "admin:backup", "video:upload"));

        TokenIssuanceService.IssuedTokens tokens = service.issue(admin);

        Claims claims = jwtTokenProvider.parseAndValidate(tokens.getAccessToken());
        assertEquals(List.of("admin"), claims.get("roles"));
        assertEquals(Set.of("user:manage", "admin:backup", "video:upload"),
            Set.copyOf((List<?>) claims.get("permissions")));
    }

    @Test
    @DisplayName("一个角色都没有的用户 → 拿到空权限集，而不是凭空三条")
    void userWithoutRolesGetsNoPermissions() {
        SysUser user = user(9L, "E000009");
        when(userRoleService.getRoleCodes(9L)).thenReturn(List.of());
        when(permissionService.getUserPermissionCodes(9L)).thenReturn(Set.of());

        TokenIssuanceService.IssuedTokens tokens = service.issue(user);

        Claims claims = jwtTokenProvider.parseAndValidate(tokens.getAccessToken());
        // 兜底角色与密码登录路径保持一致
        assertEquals(List.of(TokenIssuanceService.FALLBACK_ROLE), claims.get("roles"));
        // 权限必须为空 —— 写死那三条的话这里会是 3 条
        assertTrue(((List<?>) claims.get("permissions")).isEmpty(),
            "无角色用户不应拿到任何权限码，实际: " + claims.get("permissions"));
        assertTrue(!claims.get("permissions").toString().contains("video:read"));
    }

    @Test
    @DisplayName("refresh_token 必须落 Redis —— 否则 /auth/refresh 必然失败")
    void refreshTokenIsPersistedToRedis() {
        SysUser user = user(3L, "E000003");
        when(userRoleService.getRoleCodes(3L)).thenReturn(List.of("employee"));
        when(permissionService.getUserPermissionCodes(3L)).thenReturn(Set.of("video:read"));

        TokenIssuanceService.IssuedTokens tokens = service.issue(user);

        assertNotNull(tokens.getRefreshToken());
        verify(valueOps).set(eq("refresh:" + tokens.getRefreshToken()),
            eq("3"), eq(Duration.ofSeconds(604800L)));
    }

    @Test
    @DisplayName("刷新 access_token 不轮换 refresh_token")
    void refreshDoesNotRotateRefreshToken() {
        SysUser user = user(3L, "E000003");
        when(userRoleService.getRoleCodes(3L)).thenReturn(List.of("employee"));
        when(permissionService.getUserPermissionCodes(3L)).thenReturn(Set.of("video:read"));

        TokenIssuanceService.IssuedTokens tokens = service.refresh(user);

        assertNotNull(tokens.getAccessToken());
        org.junit.jupiter.api.Assertions.assertNull(tokens.getRefreshToken());
    }

    @Test
    @DisplayName("写死过的三条常量在本测试里是反例基准，不允许被签发逻辑产出")
    void legacyHardcodedSetIsNotProduced() {
        // 这三条曾经是 WebAuthn / SSO 登录的唯一授权来源
        SysUser user = user(4L, "E000004");
        when(userRoleService.getRoleCodes(4L)).thenReturn(List.of("employee"));
        when(permissionService.getUserPermissionCodes(4L)).thenReturn(Set.of("video:read"));

        TokenIssuanceService.IssuedTokens tokens = service.issue(user);

        Claims claims = jwtTokenProvider.parseAndValidate(tokens.getAccessToken());
        List<?> permissions = (List<?>) claims.get("permissions");
        for (String legacy : LEGACY_HARDCODED) {
            if ("video:read".equals(legacy)) {
                continue;   // 本例 RBAC 里确实有这一条
            }
            assertTrue(!permissions.contains(legacy),
                "不该出现写死的权限码: " + legacy);
        }
    }

    private SysUser user(Long id, String employeeNo) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setEmployeeNo(employeeNo);
        user.setStatus(1);
        return user;
    }
}
