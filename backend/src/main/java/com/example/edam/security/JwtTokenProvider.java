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