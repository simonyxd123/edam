package com.example.edam.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放 token 的"绑定"语义单测。
 *
 * <p><b>为什么必须有这个用例</b>：m3u8 / segment / key 三个端点在
 * Spring Security 层是 {@code permitAll}（Hls.js 拉不了自定义头，
 * token 只能放 query string），端点内部只验签。
 *
 * <p>只验签 ⇒ **普通登录 token 也能拉任意视频的播放流**，
 * 鉴权形同虚设。所以播放 token 必须额外带
 * {@code scope=playback} 与 {@code video_id}，端点据此校验。
 */
class PlaybackTokenScopeTest {

    private static final String SECRET =
        "dev-jwt-secret-must-be-at-least-256-bits-long-for-hmac-sha256";

    private JwtTokenProvider provider() {
        JwtTokenProvider p = new JwtTokenProvider(SECRET);
        // TTL 不重要，这里只是要一个能签/验的 provider
        ReflectionTestUtils.setField(p, "accessTokenTtlSeconds", 600);
        return p;
    }

    @Test
    @DisplayName("播放 token 能通过自己视频的校验")
    void playbackTokenAcceptedForItsVideo() {
        JwtTokenProvider p = provider();
        String token = p.createPlaybackToken(20L, 7L, "sess-1");

        Claims claims = p.parseAndValidate(token);
        assertTrue(p.isPlaybackTokenFor(claims, 7L),
            "播放 token 必须能通过它自己那个视频的校验");
    }

    @Test
    @DisplayName("播放 token 不能用于别的视频（video_id 不匹配）")
    void playbackTokenRejectedForOtherVideo() {
        JwtTokenProvider p = provider();
        String token = p.createPlaybackToken(20L, 7L, "sess-1");

        Claims claims = p.parseAndValidate(token);
        assertFalse(p.isPlaybackTokenFor(claims, 8L),
            "A 视频的播放 token 不能拉 B 视频");
    }

    @Test
    @DisplayName("普通登录 token 不能当播放 token 用（没有 scope=playback）")
    void plainAccessTokenRejected() {
        JwtTokenProvider p = provider();
        String token = p.createAccessToken(20L, "sess-1",
            java.util.List.of("employee"),
            java.util.List.of("video:read", "playback:read"));

        Claims claims = p.parseAndValidate(token);
        assertFalse(p.isPlaybackTokenFor(claims, 7L),
            "普通登录 token 没有 scope=playback ⇒ 不能拉播放流");
    }

    @Test
    @DisplayName("播放 token 带 video_id claim")
    void playbackTokenCarriesVideoId() {
        JwtTokenProvider p = provider();
        String token = p.createPlaybackToken(20L, 7L, "sess-1");

        Claims claims = p.parseAndValidate(token);
        assertEquals("20", claims.getSubject());
        assertEquals(JwtTokenProvider.SCOPE_PLAYBACK, claims.get("scope", String.class));
        assertEquals(7, ((Number) claims.get("video_id")).intValue());
    }

    @Test
    @DisplayName("另一个用户的播放 token 也不能用于本视频之外的视频")
    void tokensAreNotInterchangeable() {
        JwtTokenProvider p = provider();
        String mine = p.createPlaybackToken(20L, 7L, "sess-1");
        String theirs = p.createPlaybackToken(30L, 7L, "sess-2");

        assertTrue(p.isPlaybackTokenFor(p.parseAndValidate(mine), 7L));
        assertTrue(p.isPlaybackTokenFor(p.parseAndValidate(theirs), 7L),
            "同一个视频、同样的 view 权限 ⇒ 两个 token 都该有效");
        assertFalse(p.isPlaybackTokenFor(p.parseAndValidate(theirs), 8L));
    }
}
