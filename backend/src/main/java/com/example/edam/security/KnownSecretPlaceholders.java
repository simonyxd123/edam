package com.example.edam.security;

import java.util.List;
import java.util.Locale;

/**
 * 仓库里<b>公开可见</b>的占位密钥清单。
 *
 * <p><b>为什么单独抽出来</b>：本项目有三处密钥来源，兜底值全是公开的：
 * <ul>
 *   <li>{@code application.yml}：{@code ${JWT_SECRET:dev-jwt-secret-must-be-...}}</li>
 *   <li>{@code helm/edam/values.yaml}（base）：{@code change-me-in-production...}</li>
 *   <li>{@code gitops/overlays/prod/values.yaml}：{@code REPLACE_FROM_VAULT}</li>
 * </ul>
 * 每个守卫（JWT 签名密钥、Worker 回调共享密钥）都要认这批值 ——
 * 复制两份清单，迟早只改一处，另一处继续放行公开密钥。
 *
 * <p><b>只比对"归一化后完全相等"</b>，不做前缀/包含匹配：
 * 包含匹配会把真实密钥里恰好含 "secret" 的情况误判成占位符。
 *
 * <p>⚠️ 这些值本来就在 git 里，<b>不构成泄漏</b> ——
 * 所以守卫的日志里可以打印"命中了哪个占位符"，但**绝不打印实际生效的密钥**
 * （连哈希都不打）。
 */
public final class KnownSecretPlaceholders {

    private KnownSecretPlaceholders() {
    }

    /**
     * 已知占位符全集（归一化后比对）。
     *
     * <p>覆盖三种来源：
     * <ol>
     *   <li>application.yml 的兜底默认值 —— 忘注入环境变量时会用的那个</li>
     *   <li>helm base chart 的默认值 —— overlay 没覆盖时继承的</li>
     *   <li>gitops overlay 的占位符 —— 提醒运维去 Vault 换真值</li>
     * </ol>
     */
    public static final List<String> ALL = List.of(
        // application.yml 的兜底值
        "dev-jwt-secret-must-be-at-least-256-bits-long-for-hmac-sha256",
        // helm base chart 的默认值
        "change-me-in-production-must-be-256-bit",
        "change-me-in-production",
        // gitops overlay 的占位符
        "replace_from_vault",
        // dev overlay 的其它公开值
        "dev-password",
        "dev-token",
        // 其它常见的"忘了改"
        "changeme", "change-me", "secret", "jwt-secret", "your-secret-key"
    );

    /** 开发 overlay 专用的公开值 —— 非开发环境不允许。 */
    public static final String DEV_OVERLAY_SECRET = "dev-jwt-secret-not-for-production";

    /**
     * 命中已知占位符则返回该占位符（归一化后的原文），否则返回 null。
     *
     * @param secret 实际生效的密钥（可为空）
     */
    public static String match(String secret) {
        if (secret == null) {
            return null;
        }
        String normalized = secret.trim().toLowerCase(Locale.ROOT);
        for (String p : ALL) {
            if (p.equals(normalized)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 是否是开发 overlay 专用的那个公开值。
     */
    public static boolean isDevOverlayValue(String secret) {
        if (secret == null) {
            return false;
        }
        return DEV_OVERLAY_SECRET.equals(secret.trim().toLowerCase(Locale.ROOT));
    }
}
