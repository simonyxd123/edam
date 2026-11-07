package com.example.edam.security;

import com.example.edam.crypto.AlgorithmRouter;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 国密 JWT 链路的单测。
 *
 * <p><b>这条测试存在的理由</b>：v3.4.1 之前 {@code SmJwtTokenProvider}
 * 有三个 bug，**任何 GS256 token 都用不了**：
 * <ol>
 *   <li>{@code parseSm3Token} 调 {@code Jwts.parser().parseSignedClaims(token)}
 *       —— jjwt 不认识 GS256 ⇒ 必然抛异常</li>
 *   <li>claim 名对不上：写 {@code sid}，过滤器读 {@code session_id}；
 *       且<b>没有 permissions claim</b> ⇒ 所有 {@code @PreAuthorize} 全 403</li>
 *   <li>payload 手工拼 JSON，角色码里的引号/反斜杠没转义</li>
 * </ol>
 *
 * <p>而且它<b>零调用</b> —— 一段永远跑不到的代码，怎么测都"绿"。
 * 现在它接在 {@link JwtTokenProvider} 后面（{@code edam.jwt.algorithm=GS256}），
 * 这条测试就是它的第一道防线。
 */
class SmJwtTokenProviderTest {

    /**
     * **占位用的签名密钥** —— 只为满足 HS256/GS256 的最小长度校验，
     * 永远不会被部署到任何环境，不是凭据。
     */
    private static final String DUMMY_SIGNING_KEY =
        "placeholder-signing-key-for-unit-tests-only-not-a-credential";

    private AlgorithmRouter algorithmRouter;
    private SmJwtTokenProvider smProvider;

    @BeforeEach
    void setUp() {
        algorithmRouter = new AlgorithmRouter();
        smProvider = new SmJwtTokenProvider(algorithmRouter);
        ReflectionTestUtils.setField(smProvider, "jwtSecret", DUMMY_SIGNING_KEY);
        ReflectionTestUtils.setField(smProvider, "accessTokenTtl", 600L);
    }

    @Test
    @DisplayName("GS256 token 能签也能验（此前必然抛异常）")
    void signAndVerifyRoundTrip() {
        String token = smProvider.createAccessToken(
            7L, "session-1", List.of("admin"), List.of("user:manage", "video:read"));

        assertTrue(SmJwtTokenProvider.isSm3Token(token));

        Claims claims = smProvider.parseToken(token);
        assertEquals("7", claims.getSubject());
        assertNotNull(claims.get("user_id_hash"));
    }

    @Test
    @DisplayName("claim 命名与 HS256 完全一致 —— 权限码要能读出来")
    void claimNamesMatchHs256() {
        String token = smProvider.createAccessToken(
            7L, "session-1", List.of("dept_manager"),
            List.of("video:read", "video:upload", "document:read"));

        Claims claims = smProvider.parseToken(token);

        assertEquals("session-1", claims.get("session_id", String.class));
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) claims.get("roles");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) claims.get("permissions");
        assertEquals(List.of("dept_manager"), roles);
        assertEquals(List.of("video:read", "video:upload", "document:read"), permissions);
    }

    @Test
    @DisplayName("权限码为空时是空列表，不是 null")
    void emptyPermissionsBecomeEmptyList() {
        String token = smProvider.createAccessToken(7L, "s", List.of(), null);

        Claims claims = smProvider.parseToken(token);
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) claims.get("permissions");
        assertNotNull(permissions);
        assertTrue(permissions.isEmpty());
    }

    @Test
    @DisplayName("角色码里的引号 / 反斜杠不会拼出非法 JSON")
    void rolesWithSpecialCharsAreEscaped() {
        // 此前 payload 是 String.format 手工拼的 —— 这两个角色码会让 JSON 直接坏掉
        List<String> roles = List.of("we\"ird", "back\\slash");

        String token = smProvider.createAccessToken(7L, "s", roles, List.of("video:read"));

        Claims claims = smProvider.parseToken(token);
        @SuppressWarnings("unchecked")
        List<String> parsed = (List<String>) claims.get("roles");
        assertEquals(roles, parsed);
    }

    @Test
    @DisplayName("签名被篡改 → 验签失败")
    void tamperedSignatureRejected() {
        String token = smProvider.createAccessToken(
            7L, "session-1", List.of("admin"), List.of("user:manage"));

        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 2) + "AA";

        assertThrows(IllegalArgumentException.class, () -> smProvider.parseToken(tampered));
    }

    @Test
    @DisplayName("payload 被篡改 → 验签失败")
    void tamperedPayloadRejected() {
        String token = smProvider.createAccessToken(
            7L, "session-1", List.of("employee"), List.of("video:read"));

        String[] parts = token.split("\\.");
        String evilPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("{\"sub\":\"1\",\"roles\":[\"admin\"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + evilPayload + "." + parts[2];

        assertThrows(IllegalArgumentException.class, () -> smProvider.parseToken(tampered));
    }

    @Test
    @DisplayName("HS256 token 不会被误判成 GS256")
    void hs256TokenNotDetectedAsSm3() {
        JwtTokenProvider hs = new JwtTokenProvider(DUMMY_SIGNING_KEY);
        ReflectionTestUtils.setField(hs, "accessTokenTtlSeconds", 600L);

        String token = hs.createAccessToken(
            7L, "session-1", List.of("admin"), List.of("user:manage"));

        assertFalse(SmJwtTokenProvider.isSm3Token(token));
    }

    @Test
    @DisplayName("播放 token 的 claim 也一致（scope + video_id）")
    void playbackTokenClaimsMatch() {
        String token = smProvider.createPlaybackToken(7L, 42L, "session-1");

        Claims claims = smProvider.parseToken(token);
        assertEquals(JwtTokenProvider.SCOPE_PLAYBACK, claims.get("scope", String.class));
        assertEquals(42, ((Number) claims.get("video_id")).intValue());
    }

    @Test
    @DisplayName("JwtTokenProvider 切到 GS256 后，签发与验签都走国密")
    void providerDelegatesWhenGmsmEnabled() {
        JwtTokenProvider provider = new JwtTokenProvider(DUMMY_SIGNING_KEY, "GS256", smProvider);
        assertTrue(provider.isGmsmEnabled());

        String token = provider.createAccessToken(
            7L, "session-1", List.of("admin"), List.of("user:manage"));
        assertTrue(SmJwtTokenProvider.isSm3Token(token));

        // provider.parseAndValidate 必须能验国密 token —— 过滤器走的就是它
        Claims claims = provider.parseAndValidate(token);
        assertEquals("7", claims.getSubject());
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) claims.get("permissions");
        assertEquals(List.of("user:manage"), permissions);
    }

    @Test
    @DisplayName("默认仍是 HS256 —— 不开国密不影响现有部署")
    void defaultRemainsHs256() {
        JwtTokenProvider provider = new JwtTokenProvider(DUMMY_SIGNING_KEY);
        ReflectionTestUtils.setField(provider, "accessTokenTtlSeconds", 600L);
        assertFalse(provider.isGmsmEnabled());

        String token = provider.createAccessToken(
            7L, "session-1", List.of("admin"), List.of("user:manage"));
        assertFalse(SmJwtTokenProvider.isSm3Token(token));
        assertEquals("7", provider.parseAndValidate(token).getSubject());
    }

    @Test
    @DisplayName("过期的国密 token 必须被拒（手工解析不会自动校验 exp）")
    void expiredTokenRejected() {
        // 手工解析 payload 绕开了 jjwt 的过期校验 —— 必须自己查
        ReflectionTestUtils.setField(smProvider, "accessTokenTtl", -1L);

        String token = smProvider.createAccessToken(
            7L, "session-1", List.of("admin"), List.of("user:manage"));

        assertThrows(IllegalArgumentException.class, () -> smProvider.parseToken(token));
    }
}
