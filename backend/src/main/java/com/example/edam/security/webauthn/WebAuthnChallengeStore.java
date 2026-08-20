package com.example.edam.security.webauthn;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * WebAuthn Challenge 存储（v3.3 W-5.1）
 *
 * Challenge 防重放，必须短期且一次性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebAuthnChallengeStore {

    private static final String CHALLENGE_PREFIX = "webauthn:challenge:";
    private static final SecureRandom RNG = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final WebAuthnProperties properties;

    /**
     * 生成新的 challenge（32 字节随机）
     */
    public String generateChallenge() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 保存 challenge（短期 TTL）
     *
     * @param purpose "register" 或 "login"
     * @param key     用户标识（employee_no / session）
     */
    public void saveChallenge(String purpose, String key, String challenge) {
        String redisKey = CHALLENGE_PREFIX + purpose + ":" + key;
        redisTemplate.opsForValue().set(redisKey, challenge,
            Duration.ofSeconds(properties.getChallengeTtlSeconds()));
        log.debug("webauthn_challenge_saved purpose={} ttl={}s", purpose,
            properties.getChallengeTtlSeconds());
    }

    /**
     * 消费 challenge（一次性，验证后立即删除）
     *
     * @return true 验证通过；false 已过期或不存在
     */
    public boolean consumeChallenge(String purpose, String key, String expected) {
        String redisKey = CHALLENGE_PREFIX + purpose + ":" + key;
        Optional<String> stored = Optional.ofNullable(
            redisTemplate.opsForValue().getAndDelete(redisKey));

        if (stored.isEmpty()) {
            log.warn("webauthn_challenge_missing_or_expired purpose={} key={}", purpose, key);
            return false;
        }

        boolean match = constantTimeEquals(stored.get(), expected);
        if (!match) {
            log.warn("webauthn_challenge_mismatch purpose={} key={}", purpose, key);
        }
        return match;
    }

    /**
     * 常数时间字符串比较（防时间攻击）
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}