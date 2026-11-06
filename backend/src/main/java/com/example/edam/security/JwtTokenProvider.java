package com.example.edam.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * JWT Token Provider
 * - 签发 access_token（5-10 分钟）
 * - 签发 refresh_token（7 天）
 * - 解析 + 验证
 *
 * 设计原则（参考 openapi.yaml 与 ADR-0003）：
 * - Token 不携带 PII（如姓名、工号明文）
 * - 仅携带 user_id_hash（SHA-256）
 * - 使用 HMAC-SHA256 签名
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;

    @Value("${edam.jwt.access-token-ttl-seconds:600}")
    private long accessTokenTtlSeconds;

    @Value("${edam.jwt.refresh-token-ttl-seconds:604800}")
    private long refreshTokenTtlSeconds;

    public JwtTokenProvider(@Value("${edam.jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 access_token
     * @param roles      角色 code 列表（如 ['admin', 'employee']）
     * @param permissions 权限 code 列表（如 ['*:*'] 或 ['video:read', 'document:read', ...]）
     */
    public String createAccessToken(Long userId, String sessionId,
                                   List<String> roles, List<String> permissions) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtlSeconds * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of(
                    "user_id_hash", hashUserId(userId),
                    "session_id", sessionId,
                    "roles", roles,
                    "permissions", permissions != null ? permissions : List.of()
                ))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 签发**播放专用** token（绑定到某一个视频）。
     *
     * <p>为什么不能直接复用 {@link #createAccessToken}：
     * m3u8 / segment / key 三个端点在 Spring Security 层是 {@code permitAll}
     * （Hls.js 拉不了自定义头，只能把 token 放 query string），
     * 而端点内部只验签 —— 于是**任何**合法 JWT（包括普通登录 token）
     * 都能拿来拉任意视频的播放流，鉴权形同虚设。
     *
     * <p>所以播放 token 额外带两个 claim：
     * <ul>
     *   <li>{@code scope=playback} —— 标记"这是播放 token"，普通登录 token 没有</li>
     *   <li>{@code video_id} —— 绑定到具体视频，拿 A 的 token 拉不了 B</li>
     * </ul>
     * 端点侧据此校验：token 必须是播放 token、且绑定到请求的那个视频。
     *
     * @param userId  看视频的人
     * @param videoId 这个 token 只对这一个视频有效
     */
    public String createPlaybackToken(Long userId, Long videoId, String sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtlSeconds * 1000);

        Map<String, Object> claims = new HashMap<>();
        claims.put("user_id_hash", hashUserId(userId));
        claims.put("session_id", sessionId);
        claims.put("scope", SCOPE_PLAYBACK);
        claims.put("video_id", videoId);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /** 播放 token 的 scope claim 值 */
    public static final String SCOPE_PLAYBACK = "playback";

    /**
     * 校验一个 token 是不是"某个指定视频的播放 token"。
     *
     * @param claims  已验签的 claims
     * @param videoId 请求的视频
     * @return 校验通过返回 true
     */
    public boolean isPlaybackTokenFor(Claims claims, Long videoId) {
        if (claims == null) {
            return false;
        }
        if (!SCOPE_PLAYBACK.equals(claims.get("scope", String.class))) {
            return false;
        }
        Object vid = claims.get("video_id");
        return vid != null && String.valueOf(vid).equals(String.valueOf(videoId));
    }

    /**
     * access_token 有效期（秒）—— 供响应体 {@code expires_in} 与
     * refresh_token 落库 TTL 使用，避免各处硬编码 600 / 7 天。
     */
    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    /**
     * refresh_token 有效期（秒）
     */
    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    /**
     * 签发 refresh_token（不透明字符串）
     */
    public String createRefreshToken() {
        // 实际生产环境：使用 SecureRandom 生成 64 字节随机字符串
        return java.util.UUID.randomUUID().toString().replace("-", "") +
               java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 解析并验证 token
     */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 计算 user_id 的 SHA-256 哈希（不暴露明文）
     */
    private String hashUserId(Long userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}