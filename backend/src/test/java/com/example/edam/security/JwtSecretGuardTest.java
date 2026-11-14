package com.example.edam.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JwtSecretGuard} 的单测。
 *
 * <p><b>这条守卫存在的理由</b>：JWT 是无状态自证的 —— 服务端只验签。
 * 签名密钥一旦是"仓库里公开可见的值"（{@code application.yml} 的兜底默认值、
 * helm base 的 {@code change-me-in-production...}、prod overlay 的
 * {@code REPLACE_FROM_VAULT}），<b>任何能读到这个仓库的人都能自己签出
 * {@code permissions:["*:*"]} 的管理员 token</b> —— 整个鉴权被绕过。
 *
 * <p>所以这里盯的是"占位符 + 生产 profile ⇒ 拒绝启动"。
 */
class JwtSecretGuardTest {

    private Environment envWith(String... profiles) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(profiles);
        return env;
    }

    @Test
    @DisplayName("生产 profile + 仓库里公开的占位符 ⇒ 拒绝启动")
    void productionWithPublicPlaceholderFailsFast() {
        JwtSecretGuard guard = new JwtSecretGuard(
            envWith("production"),
            "change-me-in-production-must-be-256-bit");
        IllegalStateException e = assertThrows(IllegalStateException.class, guard::validate);
        assertTrue(e.getMessage().contains("占位符"), "异常要说清是占位符问题: " + e.getMessage());
    }

    @Test
    @DisplayName("生产 profile + REPLACE_FROM_VAULT ⇒ 拒绝启动")
    void productionWithVaultPlaceholderFailsFast() {
        JwtSecretGuard guard = new JwtSecretGuard(envWith("production"), "REPLACE_FROM_VAULT");
        assertThrows(IllegalStateException.class, guard::validate);
    }

    @Test
    @DisplayName("生产 profile + application.yml 的兜底默认值 ⇒ 拒绝启动")
    void productionWithApplicationYmlDefaultFailsFast() {
        JwtSecretGuard guard = new JwtSecretGuard(envWith("production"),
            "dev-jwt-secret-must-be-at-least-256-bits-long-for-hmac-sha256");
        assertThrows(IllegalStateException.class, guard::validate);
    }

    @Test
    @DisplayName("开发 overlay 的公开密钥 ⇒ 生产环境拒绝，开发环境放行")
    void devOverlaySecretOnlyAllowedOutsideProduction() {
        assertThrows(IllegalStateException.class, () -> new JwtSecretGuard(
            envWith("production"), "dev-jwt-secret-not-for-production").validate());

        assertDoesNotThrow(() -> new JwtSecretGuard(
            envWith("dev"), "dev-jwt-secret-not-for-production").validate());
    }

    @Test
    @DisplayName("非生产 profile + 占位符 ⇒ 放行（本地开发与单测要用）")
    void nonProductionWithPlaceholderIsAllowed() {
        assertDoesNotThrow(() -> new JwtSecretGuard(
            envWith("dev"), "dev-jwt-secret-not-for-production").validate());
        assertDoesNotThrow(() -> new JwtSecretGuard(
            envWith(), "change-me-in-production-must-be-256-bit").validate());
    }

    @Test
    @DisplayName("密钥为空 / 太短 ⇒ 一律拒绝启动（任何 profile）")
    void emptyOrShortSecretAlwaysFails() {
        assertThrows(IllegalStateException.class,
            () -> new JwtSecretGuard(envWith("dev"), "").validate());
        assertThrows(IllegalStateException.class,
            () -> new JwtSecretGuard(envWith("dev"), "too-short").validate());
    }

    @Test
    @DisplayName("真实随机密钥 ⇒ 放行")
    void realSecretPasses() {
        assertDoesNotThrow(() -> new JwtSecretGuard(
            envWith("production"),
            "3f9a1c7e5b2d8046a1f3c9e7d5b2048ac1e7d3b95f204a6c8e1d7b3f5a9c2e604d"
        ).validate());
    }
}
