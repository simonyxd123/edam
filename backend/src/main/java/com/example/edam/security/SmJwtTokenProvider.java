package com.example.edam.security;

import com.example.edam.crypto.AlgorithmRouter;
import com.example.edam.crypto.gmsm.SM3Util;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * JWT SM3 国密签名 Provider（v3.3 W-2.4）
 *
 * 替代原 HMAC-SHA256 签名算法为 SM3-HMAC（国密）
 *
 * 用法：
 * ```java
 * String token = jwtTokenProvider.createAccessToken(userId, sessionId, roles);
 * Claims claims = jwtTokenProvider.parseToken(token);
 * ```
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmJwtTokenProvider {

    @Value("${edam.jwt.secret:dev-jwt-secret-must-be-at-least-256-bits-long-for-hmac-sha256}")
    private String jwtSecret;

    @Value("${edam.jwt.access-token-ttl-seconds:600}")
    private long accessTokenTtl;

    @Value("${edam.jwt.algorithm:HS256}")
    private String algorithm;  // HS256 = HMAC-SHA256; GS256 = GM SM3

    private final AlgorithmRouter algorithmRouter;

    /**
     * 创建 access_token（默认 5-10 分钟）
     */
    public String createAccessToken(Long userId, String sessionId, List<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtl * 1000);

        String algorithmToUse = "GS256".equals(algorithm) ? "GS256" : "HS256";

        // 自定义 SM3-HMAC 签名
        if ("GS256".equals(algorithmToUse)) {
            return createWithSm3(userId, sessionId, roles, now, expiry);
        } else {
            return createWithHmacSha256(userId, sessionId, roles, now, expiry);
        }
    }

    /**
     * 使用 SM3-HMAC 签名（国密）
     */
    private String createWithSm3(Long userId, String sessionId, List<String> roles,
                                  Date now, Date expiry) {
        try {
            // 构造 header.payload
            String header = "{\"alg\":\"GS256\",\"typ\":\"JWT\"}";
            String payload = String.format(
                "{\"sub\":\"%s\",\"sid\":\"%s\",\"roles\":%s,\"iat\":%d,\"exp\":%d}",
                userId, sessionId, toJsonArray(roles),
                now.getTime() / 1000, expiry.getTime() / 1000
            );

            String headerB64 = base64UrlEncode(header.getBytes());
            String payloadB64 = base64UrlEncode(payload.getBytes());
            String signingInput = headerB64 + "." + payloadB64;

            // SM3-HMAC 签名
            byte[] keyBytes = jwtSecret.getBytes();
            String signature = SM3Util.hmac(keyBytes, signingInput.getBytes());
            String signatureB64 = base64UrlEncode(signature.getBytes());

            return signingInput + "." + signatureB64;
        } catch (Exception e) {
            log.error("sm3_jwt_sign_failed", e);
            throw new RuntimeException("SM3 JWT sign failed", e);
        }
    }

    /**
     * 使用 HMAC-SHA256 签名（国际）
     */
    private String createWithHmacSha256(Long userId, String sessionId, List<String> roles,
                                         Date now, Date expiry) {
        Key key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("sid", sessionId)
            .claim("roles", roles)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();
    }

    /**
     * 解析 token
     */
    public Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token is empty");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        // 解析 header
        String headerJson = new String(base64UrlDecode(parts[0]));
        boolean isSm3 = headerJson.contains("GS256");

        if (isSm3) {
            return parseSm3Token(token, parts);
        } else {
            return parseHmacToken(token);
        }
    }

    private Claims parseSm3Token(String token, String[] parts) {
        try {
            String signingInput = parts[0] + "." + parts[1];
            String providedSignature = new String(base64UrlDecode(parts[2]));

            // 验签
            String expectedSignature = SM3Util.hmac(jwtSecret.getBytes(), signingInput.getBytes());

            // 注意：hex 字符串直接比较
            if (!providedSignature.equalsIgnoreCase(expectedSignature)) {
                throw new IllegalArgumentException("Invalid JWT signature");
            }

            // 解析 payload
            String payloadJson = new String(base64UrlDecode(parts[1]));
            Claims claims = Jwts.parser()
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return claims;
        } catch (Exception e) {
            throw new IllegalArgumentException("SM3 JWT parse failed: " + e.getMessage(), e);
        }
    }

    private Claims parseHmacToken(String token) {
        Key key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        return Jwts.parser()
            .verifyWith((javax.crypto.SecretKey) key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public String createRefreshToken() {
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    // ---- 工具方法 ----

    private static String base64UrlEncode(byte[] data) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] base64UrlDecode(String data) {
        return java.util.Base64.getUrlDecoder().decode(data);
    }

    private static String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(list.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}