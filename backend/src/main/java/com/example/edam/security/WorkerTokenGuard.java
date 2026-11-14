package com.example.edam.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Worker 回调共享密钥守卫：<b>拒绝"仓库里公开可见的占位密钥"</b>。
 *
 * <p><b>为什么这条比 JWT 那条更危险</b>：
 * {@code edam.worker.token} 是 Worker → Backend 的共享密钥，
 * 持有它就能调用这两个端点：
 * <pre>
 *   PATCH /api/v1/documents/{id}/status
 *   PATCH /api/v1/videos/{id}/status
 * </pre>
 * 而这两个端点能做的事是：
 * <ul>
 *   <li>把<b>任意</b>文档的 {@code watermark_status} 改成 2(ready)
 *       ⇒ 一份<b>根本没有水印</b>的文档被标记成"已加水印"放行，
 *       溯源链就此断裂，而且系统自己还认为它是完好的</li>
 *   <li>写入<b>任意</b>指纹到水印指纹登记表（第六轮新增）
 *       ⇒ 事后比对时"对不上" ⇒ 真的泄密被判定为"伪造"而洗白；
 *       或者反过来，栽赃给一个无辜员工</li>
 * </ul>
 * 也就是说，**拿到这个密钥就等于拿到了改写溯源结论的能力**。
 *
 * <p><b>而这个密钥在仓库里就是公开的</b>：
 * <ul>
 *   <li>{@code helm/edam/values.yaml}（base）：{@code change-me-in-production}</li>
 *   <li>{@code gitops/overlays/prod/values.yaml}：{@code REPLACE_FROM_VAULT}</li>
 *   <li>staging overlay <b>连 workerToken 这一行都没有</b> ⇒ 继承 base 的公开值</li>
 * </ul>
 * External Secrets 那条链路当时是<b>断的</b>（第七轮 §1），
 * 所以"两边一致"这个约束反而把公开值<b>坐实</b>成了生产实际使用的值 ——
 * Worker 和 Backend 都读到同一个公开字符串，校验自然通过。
 *
 * <p><b>判定规则</b>：
 * <ol>
 *   <li>密钥为空 ⇒ 只告警，<b>不拒绝启动</b>
 *       （没配 worker 的部署是允许的；回调一律被拒已是 fail-closed，
 *       见 {@link WorkerTokenVerifier}）</li>
 *   <li>非开发 profile 下用了已知占位符 ⇒ <b>拒绝启动</b></li>
 *   <li>非开发 profile 下短于 16 字节 ⇒ <b>拒绝启动</b>
 *       （128 bit —— 这个密钥的权限太大，不能用短口令）</li>
 *   <li>开发 profile ⇒ 告警放行</li>
 * </ol>
 *
 * <p>⚠️ <b>日志里绝不打印密钥本身</b>（连哈希都不打）——
 * 只打印"命中了哪个已知占位符"和字节长度。
 *
 * <p>对应方案书 <b>§5.2</b>（身份认证）与 <b>§14.3 STRIDE / Spoofing</b>。
 */
@Slf4j
@Component
public class WorkerTokenGuard {

    /**
     * 允许使用公开密钥的 profile —— <b>只有开发类</b>。
     *
     * <p>与 {@link JwtSecretGuard} 同一套口径：staging 不是开发环境。
     */
    private static final Set<String> LAX_PROFILES = Set.of("dev", "local", "test");

    /** 最小长度：16 字节 = 128 bit。 */
    private static final int MIN_TOKEN_BYTES = 16;

    private final Environment env;
    private final String token;

    public WorkerTokenGuard(Environment env,
                             @Value("${edam.worker.token:}") String token) {
        this.env = env;
        this.token = token == null ? "" : token.trim();
    }

    @PostConstruct
    public void validate() {
        boolean strict = isStrictEnvironment();

        if (token.isEmpty()) {
            // 空 ≠ 公开。回调会被 WorkerTokenVerifier 一律拒绝（fail-closed），
            // 后端仍然可以单独跑（比如只提供下载与预览的实例）。
            log.warn("worker_token_not_configured, action=Worker 回调将被一律拒绝(401)，"
                + "profile={}", String.join(",", env.getActiveProfiles()));
            return;
        }

        String matched = KnownSecretPlaceholders.match(token);
        if (matched != null) {
            if (strict) {
                throw new IllegalStateException(
                    "edam.worker.token 命中了仓库里公开可见的占位符（\""
                    + matched + "\"）—— 生产环境拒绝启动。"
                    + "持有这个公开值的人可以伪造文档状态回调，"
                    + "把没水印的文档标记成已加水印、或往指纹登记表里写入伪造指纹。"
                    + "请通过 Secret / External Secrets 注入真实的随机值。");
            }
            log.warn("worker_token_is_public_placeholder, placeholder={}, "
                + "action=仅限开发/测试环境使用，溯源结论可被伪造",
                matched);
            return;
        }

        int bytes = token.getBytes(StandardCharsets.UTF_8).length;
        if (strict && bytes < MIN_TOKEN_BYTES) {
            throw new IllegalStateException(
                "edam.worker.token 只有 " + bytes + " 字节 —— 至少需要 "
                + MIN_TOKEN_BYTES + " 字节（128 bit）。拒绝启动。");
        }

        // 不打印密钥本身，也不打印它的哈希
        log.info("worker_token_guard_passed, profile={}, strict={}, bytes={}",
            String.join(",", env.getActiveProfiles()), strict, bytes);
    }

    /**
     * 是否按"生产"对待 —— 白名单语义，只 {@code dev / local / test} 放行。
     */
    private boolean isStrictEnvironment() {
        String[] profiles = env.getActiveProfiles();
        if (profiles.length == 0) {
            // 没设 profile ⇒ 本地开发
            return false;
        }
        for (String p : profiles) {
            if (p == null || !LAX_PROFILES.contains(p.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
