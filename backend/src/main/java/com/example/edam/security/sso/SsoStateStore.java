package com.example.edam.security.sso;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * SSO 状态存储（state / PKCE code_verifier / 一次性登录码）。
 *
 * <p><b>为什么必须有</b>：
 * <ul>
 *   <li><b>state</b> 是 OAuth2/SAML 回调的 CSRF 防护。登录时生成并
 *       存起来，回调时比对并作废。原实现生成了 state 却**从不存、从不验**
 *       —— 攻击者可以把受害者的 IdP 回调 URL 塞给受害者点，
 *       触发"登录 CSRF"（受害者被登录进攻击者账号）。</li>
 *   <li><b>code_verifier</b> 是 PKCE 的核心。原实现生成了 verifier 和
 *       challenge，但 verifier **立刻丢弃**，换 token 时也不发送
 *       {@code code_verifier} —— 任何合规 IdP 都会拒绝这种请求
 *       （等于 PKCE 白做）。</li>
 *   <li><b>一次性登录码</b>：SSO 成功后如果把 access_token 直接放进
 *       重定向 URL，会进浏览器历史、反代 access log、下游 Referer。
 *       改成重定向时只带一个一次性 code，前端用 code 换 token。</li>
 * </ul>
 *
 * <p>存储用 Redis（项目已有依赖），TTL 10 分钟，且**一次性**（取出即删）。
 */
@Slf4j
@Component
public class SsoStateStore {

    private static final String KEY_PREFIX = "sso:state:";
    private static final String CODE_PREFIX = "sso:login-code:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public SsoStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 登录时保存 state（连同 PKCE code_verifier）。
     */
    public void saveState(String providerId, String state, String codeVerifier) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state 不能为空");
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + state,
                providerId + "|" + (codeVerifier == null ? "" : codeVerifier),
                TTL);
        } catch (Exception e) {
            // Redis 不可用 → fail-closed：state 存不下就干脆别发登录请求
            log.error("sso_state_save_failed, provider_id={}", providerId, e);
            throw new IllegalStateException("SSO 状态存储不可用，已拒绝发起登录", e);
        }
    }

    /**
     * 回调时校验并消费 state。
     *
     * @param state      回调带回的 state
     * @param providerId 回调路径里的 providerId
     * @return 记录里的 code_verifier（可能为空 —— SAML 不用 PKCE）
     * @throws IllegalArgumentException state 不存在 / 已用过 / provider 不匹配
     */
    public String consumeState(String state, String providerId) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("缺少 state 参数");
        }
        String raw;
        try {
            raw = redis.opsForValue().get(KEY_PREFIX + state);
            // 一次性：无论后续校验是否通过，先删掉
            redis.delete(KEY_PREFIX + state);
        } catch (Exception e) {
            log.error("sso_state_read_failed", e);
            throw new IllegalStateException("SSO 状态校验失败", e);
        }
        if (raw == null) {
            throw new IllegalArgumentException("state 无效或已使用（可能是 CSRF 尝试）");
        }
        int sep = raw.indexOf('|');
        if (sep < 0) {
            throw new IllegalArgumentException("state 记录格式错误");
        }
        String storedProvider = raw.substring(0, sep);
        String codeVerifier = raw.substring(sep + 1);
        if (providerId != null && !providerId.equals(storedProvider)) {
            throw new IllegalArgumentException(
                "state 与 provider 不匹配: expected=" + storedProvider + " actual=" + providerId);
        }
        return codeVerifier.isEmpty() ? null : codeVerifier;
    }

    /**
     * 保存一次性登录码（access_token + refresh_token）。
     */
    public void saveLoginCode(String code, String accessToken, String refreshToken) {
        try {
            redis.opsForValue().set(CODE_PREFIX + code,
                accessToken + "|" + refreshToken, TTL);
        } catch (Exception e) {
            log.error("sso_login_code_save_failed", e);
            throw new IllegalStateException("SSO 登录码存储不可用", e);
        }
    }

    /**
     * 消费一次性登录码。
     */
    public Map<String, String> consumeLoginCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("缺少登录码");
        }
        String raw;
        try {
            raw = redis.opsForValue().get(CODE_PREFIX + code);
            redis.delete(CODE_PREFIX + code);
        } catch (Exception e) {
            log.error("sso_login_code_read_failed", e);
            throw new IllegalStateException("SSO 登录码校验失败", e);
        }
        if (raw == null) {
            throw new IllegalArgumentException("登录码无效或已使用");
        }
        int sep = raw.indexOf('|');
        if (sep < 0) {
            throw new IllegalArgumentException("登录码记录格式错误");
        }
        return Map.of(
            "access_token", raw.substring(0, sep),
            "refresh_token", raw.substring(sep + 1));
    }

    /** 供测试/运维查询 TTL */
    public Duration getTtl() {
        return TTL;
    }
}
