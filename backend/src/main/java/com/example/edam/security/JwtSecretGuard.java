package com.example.edam.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * JWT 密钥守卫：<b>拒绝"仓库里公开可见的占位密钥"</b>。
 *
 * <p><b>为什么需要它</b>：JWT 是无状态自证的 —— 服务端只验签，不查库。
 * 而签名密钥一旦泄漏，攻击者可以<b>自己签出任意 token</b>：
 * <pre>
 *   {"sub":"1","permissions":["*:*"],"exp":...}  →  直接就是管理员
 * </pre>
 * 这不是"绕过某个端点"，是<b>整个系统的鉴权被绕过</b>。
 *
 * <p>而这个仓库里，密钥的默认值是<b>明文写在配置里</b>的：
 * <ul>
 *   <li>{@code application.yml}：{@code ${JWT_SECRET:dev-jwt-secret-must-be-at-least-256-bits-long-for-hmac-sha256}}
 *       —— 忘了注入环境变量时，<b>照样能启动</b>，用的是这个公开值</li>
 *   <li>{@code helm/edam/values.yaml}（base）：{@code change-me-in-production-must-be-256-bit}</li>
 *   <li>{@code gitops/overlays/prod/values.yaml}：{@code REPLACE_FROM_VAULT}
 *       —— 占位符没被替换时，<b>生产环境就用它当签名密钥</b></li>
 * </ul>
 * 这些值都在 git 里 ⇒ 任何能读到这个仓库的人都能伪造管理员 token。
 *
 * <p><b>判定规则</b>：
 * <ol>
 *   <li>密钥为空 / 短于 32 字节 ⇒ <b>一律</b>拒绝启动
 *       （短密钥本来也会被 {@code Keys.hmacShaKeyFor} 拒掉，但那个异常
 *       出现在很后面、消息也不直白，这里提前给出可读的原因）</li>
 *   <li>生产 profile 下用了已知占位符 ⇒ <b>拒绝启动</b>（fail-closed）</li>
 *   <li>非生产 profile 下用了已知占位符 ⇒ <b>响亮告警</b>但不拒绝
 *       （本地开发与单测要用这些值，拦了会寸步难行）</li>
 * </ol>
 *
 * <p>⚠️ <b>日志里绝不打印密钥本身</b> —— 只打印"命中了哪个已知占位符"
 * （那些值本来就在仓库里，不构成泄漏）。
 *
 * <p>对应方案书 <b>§5.2</b>（身份认证）与 <b>§14.3 STRIDE / Spoofing</b>。
 */
@Slf4j
@Component
public class JwtSecretGuard {

    /**
     * 已知占位符 —— 全部是仓库里公开可见的值。
     * 归一化（小写）后比对。
     */
    private static final List<String> KNOWN_PLACEHOLDERS = List.of(
        // application.yml 的兜底值（= 没注入 JWT_SECRET 时会用的那个）
        "dev-jwt-secret-must-be-at-least-256-bits-long-for-hmac-sha256",
        // helm base chart 的默认值
        "change-me-in-production-must-be-256-bit",
        "change-me-in-production",
        // gitops overlay 的占位符
        "replace_from_vault",
        // 其它常见的"忘了改"
        "changeme", "change-me", "secret", "jwt-secret", "your-secret-key"
    );

    /** 开发 overlay 专用的值 —— 非生产环境允许（但要告警）。 */
    private static final String DEV_OVERLAY_SECRET = "dev-jwt-secret-not-for-production";

    /** HS256 要求密钥 ≥ 256 位 = 32 字节。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final Environment env;
    private final String secret;

    public JwtSecretGuard(Environment env,
                           @Value("${edam.jwt.secret:}") String secret) {
        this.env = env;
        this.secret = secret == null ? "" : secret.trim();
    }

    @PostConstruct
    public void validate() {
        if (secret.isEmpty()) {
            throw new IllegalStateException(
                "edam.jwt.secret 未配置 —— 拒绝启动。"
                + "请注入 JWT_SECRET 环境变量（至少 " + MIN_SECRET_BYTES + " 字节的随机值）。");
        }

        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "edam.jwt.secret 只有 " + bytes + " 字节 —— HS256 要求至少 "
                + MIN_SECRET_BYTES + " 字节（256 位）。拒绝启动。");
        }

        String normalized = secret.toLowerCase(Locale.ROOT);
        String matched = matchKnownPlaceholder(normalized);
        boolean production = isProductionProfile();

        if (matched != null) {
            if (production) {
                // 只打印"命中了哪个已知占位符"——那些值本来就在仓库里，
                // 不构成泄漏；**绝不打印实际生效的密钥**。
                throw new IllegalStateException(
                    "edam.jwt.secret 命中了仓库里公开可见的占位符（\""
                    + matched + "\"）—— 生产环境拒绝启动。"
                    + "请通过 Secret / External Secrets 注入真实的随机密钥。");
            }
            log.warn("jwt_secret_is_public_placeholder, placeholder={}, "
                    + "action=仅限开发/测试环境使用，密钥可被任何人伪造",
                    matched);
            return;
        }

        if (DEV_OVERLAY_SECRET.equals(normalized)) {
            if (production) {
                throw new IllegalStateException(
                    "edam.jwt.secret 使用了开发 overlay 的公开密钥 —— 生产环境拒绝启动。");
            }
            log.warn("jwt_secret_is_dev_overlay_value, action=仅限非生产环境");
            return;
        }

        // 不打印密钥本身，也不打印它的哈希
        log.info("jwt_secret_guard_passed, profile={}, production={}",
            String.join(",", env.getActiveProfiles()), production);
    }

    /** 命中已知占位符则返回该占位符（归一化后的原文），否则 null。 */
    private String matchKnownPlaceholder(String normalized) {
        for (String p : KNOWN_PLACEHOLDERS) {
            if (p.equals(normalized)) {
                return p;
            }
        }
        return null;
    }

    private boolean isProductionProfile() {
        return Arrays.stream(env.getActiveProfiles())
            .anyMatch(p -> p != null && p.toLowerCase(Locale.ROOT).contains("prod"));
    }
}
