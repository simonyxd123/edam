package com.example.edam.security;

import com.example.edam.crypto.AlgorithmRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ClaimsBuilder;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT SM3 国密签名 Provider（v3.3 W-2.4）
 *
 * <p><b>v3.4.1 已接线</b>：{@code edam.jwt.algorithm=GS256} 时，
 * {@link JwtTokenProvider} 会把签发与验签都交给本类。
 * claim 命名与 {@link JwtTokenProvider} <b>完全一致</b>
 * （{@code session_id} / {@code roles} / {@code permissions} / {@code user_id_hash}），
 * 所以 {@link JwtAuthenticationFilter} 读权限码的逻辑不需要改。
 *
 * <p><b>此前为什么完全不能用</b>（三个真 bug）：
 * <ol>
 *   <li>{@code parseSm3Token} 里调 {@code Jwts.parser().parseSignedClaims(token)}
 *       —— jjwt 不认识 {@code GS256}，<b>任何 GS256 token 都解析不出来</b></li>
 *   <li>claim 名对不上：写的是 {@code sid}，过滤器读的是 {@code session_id}；
 *       且<b>根本没有 permissions claim</b> ⇒ 所有 {@code @PreAuthorize} 都会 403</li>
 *   <li>payload 用 {@code String.format} 手工拼 JSON，角色码里的
 *       {@code "} 与 {@code \} 没有转义 ⇒ 能拼出非法 JSON</li>
 * </ol>
 *
 * <p>现在：payload 用 Jackson 序列化（自动转义），
 * GS256 的验签自己算（不借道 jjwt），签名算法经 {@link AlgorithmRouter} 选取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmJwtTokenProvider {

    /** JWT header 里的算法标识 */
    public static final String ALG_GS256 = "GS256";

    private final AlgorithmRouter algorithmRouter;

    @Value("${edam.jwt.secret:dev-jwt-secret-must-be-at-least-256-bits-long-for-hmac-sha256}")
    private String jwtSecret;

    @Value("${edam.jwt.access-token-ttl-seconds:600}")
    private long accessTokenTtl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 签发 access_token。
     *
     * <p>签名与 {@link JwtTokenProvider#createAccessToken} 完全同构 ——
     * 参数与 claim 命名都一样，切换算法不需要改任何调用方。
     */
    public String createAccessToken(Long userId, String sessionId,
                                    List<String> roles, List<String> permissions) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtl * 1000);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", String.valueOf(userId));
        payload.put("user_id_hash", hashUserId(userId));
        payload.put("session_id", sessionId);
        payload.put("roles", roles == null ? List.of() : roles);
        payload.put("permissions", permissions == null ? List.of() : permissions);
        payload.put("iat", now.getTime() / 1000);
        payload.put("exp", expiry.getTime() / 1000);
        return sign(payload);
    }

    /**
     * 签发**播放专用** token（绑定到某一个视频）。
     *
     * <p>claim 与 {@link JwtTokenProvider#createPlaybackToken} 一致
     * （{@code scope=playback} + {@code video_id}），
     * 否则播放端点的 scope 校验会对国密 token 全部失效。
     */
    public String createPlaybackToken(Long userId, Long videoId, String sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtl * 1000);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", String.valueOf(userId));
        payload.put("user_id_hash", hashUserId(userId));
        payload.put("session_id", sessionId);
        payload.put("scope", JwtTokenProvider.SCOPE_PLAYBACK);
        payload.put("video_id", videoId);
        payload.put("iat", now.getTime() / 1000);
        payload.put("exp", expiry.getTime() / 1000);
        return sign(payload);
    }

    // ==================================================================
    // 签 / 验
    // ==================================================================

    /**
     * 用 SM3-HMAC 签名一个 claim 集合。
     */
    private String sign(Map<String, Object> payload) {
        try {
            String headerJson = objectMapper.writeValueAsString(
                Map.of("alg", ALG_GS256, "typ", "JWT"));
            String payloadJson = objectMapper.writeValueAsString(payload);

            String signingInput = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8))
                + "." + base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

            // 签名算法由 AlgorithmRouter 选（这里固定要国密）
            String signature = algorithmRouter.hmac(
                AlgorithmRouter.Algorithm.SM3_HMAC,
                jwtSecret.getBytes(StandardCharsets.UTF_8),
                signingInput.getBytes(StandardCharsets.UTF_8));

            return signingInput + "." + base64UrlEncode(signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("sm3_jwt_sign_failed", e);
            throw new IllegalStateException("SM3 JWT sign failed", e);
        }
    }

    /**
     * 解析并验签 GS256 token。
     *
     * <p><b>不能借道 jjwt</b> —— jjwt 不认识 {@code GS256} 算法，
     * {@code parseSignedClaims} 会直接抛异常。所以这里手工验签 + 手工解析 payload。
     */
    public Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token is empty");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        String signingInput = parts[0] + "." + parts[1];
        String providedSignature = new String(
            base64UrlDecode(parts[2]), StandardCharsets.UTF_8);

        String expectedSignature = algorithmRouter.hmac(
            AlgorithmRouter.Algorithm.SM3_HMAC,
            jwtSecret.getBytes(StandardCharsets.UTF_8),
            signingInput.getBytes(StandardCharsets.UTF_8));

        // 常量时间比较，防 timing 旁路
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid JWT signature");
        }

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(
                new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("SM3 JWT payload parse failed: " + e.getMessage(), e);
        }

        ClaimsBuilder claims = Jwts.claims();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            claims.add(entry.getKey(), entry.getValue());
        }
        Object sub = payload.get("sub");
        if (sub != null) {
            claims.subject(String.valueOf(sub));
        }
        Claims result = claims.build();

        // 过期校验 —— 必须自己做：jjwt 的 parseSignedClaims 会顺手校验 exp，
        // 而 GS256 走的是手工解析，**不校验就等于永不过期的 token**。
        //
        // 缺 exp / exp 不是数字 ⇒ **直接拒**（fail-closed）。
        // 原实现写成 `if (exp instanceof Number ...)` —— exp 缺失时整段被跳过，
        // 等于"没有过期时间的 token 也认"。虽然签名覆盖了 payload，
        // 攻击者改不了，但签发侧一旦出岔子就是永不过期的凭证。
        Object exp = payload.get("exp");
        if (!(exp instanceof Number expSeconds)) {
            throw new IllegalArgumentException("GS256 token 缺少 exp claim");
        }
        if (expSeconds.longValue() <= System.currentTimeMillis() / 1000) {
            throw new IllegalArgumentException("JWT 已过期");
        }
        return result;
    }

    /**
     * 这个 token 是不是 GS256 签的（看 header 的 alg）。
     */
    public static boolean isSm3Token(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        try {
            String headerJson = new String(
                Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            return headerJson.contains(ALG_GS256);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String hashUserId(Long userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                String.valueOf(userId).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] base64UrlDecode(String data) {
        return Base64.getUrlDecoder().decode(data);
    }
}
