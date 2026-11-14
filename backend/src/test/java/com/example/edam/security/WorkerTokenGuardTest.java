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
 * {@link WorkerTokenGuard} 的单测。
 *
 * <p><b>这条守卫存在的理由</b>：{@code edam.worker.token} 是 Worker → Backend
 * 的共享密钥，持有它就能调用 {@code PATCH /documents/{id}/status} ——
 * 把任意文档标成"已加水印"（溯源链断裂），或者往水印指纹登记表里写入
 * 伪造指纹（洗白真的泄密，或者栽赃无辜员工）。
 *
 * <p>而这个值在仓库里就是公开的：helm base 是 {@code change-me-in-production}，
 * prod overlay 是 {@code REPLACE_FROM_VAULT}，staging overlay 连这一行都没写
 * ⇒ 直接继承 base 的公开值。External Secrets 那条链路当时是<b>断的</b>，
 * 于是"两边一致"这个约束反而把公开值<b>坐实</b>成了生产实际使用的值。
 */
class WorkerTokenGuardTest {

    private Environment envWith(String... profiles) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(profiles);
        return env;
    }

    @Test
    @DisplayName("生产 profile + 仓库里公开的占位符 ⇒ 拒绝启动")
    void productionWithPublicPlaceholderFailsFast() {
        WorkerTokenGuard guard = new WorkerTokenGuard(
            envWith("production"), "REPLACE_FROM_VAULT");
        IllegalStateException e = assertThrows(IllegalStateException.class, guard::validate);
        assertTrue(e.getMessage().contains("占位符"), "异常要说清是占位符问题: " + e.getMessage());
    }

    @Test
    @DisplayName("helm base 的占位符在生产同样拒绝启动")
    void productionWithHelmBasePlaceholderFailsFast() {
        WorkerTokenGuard guard = new WorkerTokenGuard(
            envWith("production"), "change-me-in-production");
        assertThrows(IllegalStateException.class, guard::validate);
    }

    @Test
    @DisplayName("staging 也按生产对待（它连着真实依赖，不是开发环境）")
    void stagingIsTreatedAsProduction() {
        WorkerTokenGuard guard = new WorkerTokenGuard(
            envWith("staging"), "REPLACE_FROM_VAULT");
        assertThrows(IllegalStateException.class, guard::validate);
    }

    @Test
    @DisplayName("开发 profile + 占位符 ⇒ 放行（本地开发要用）")
    void devWithPlaceholderIsAllowed() {
        assertDoesNotThrow(() -> new WorkerTokenGuard(
            envWith("dev"), "change-me-in-production").validate());
    }

    @Test
    @DisplayName("没配 token ⇒ 不拒绝启动（回调本来就会被 WorkerTokenVerifier 一律拒绝）")
    void emptyTokenDoesNotBlockStartup() {
        assertDoesNotThrow(() -> new WorkerTokenGuard(envWith("production"), "").validate());
        assertDoesNotThrow(() -> new WorkerTokenGuard(envWith("production"), null).validate());
    }

    @Test
    @DisplayName("生产 profile + 太短的 token ⇒ 拒绝启动")
    void productionWithTooShortTokenFailsFast() {
        WorkerTokenGuard guard = new WorkerTokenGuard(envWith("production"), "short-token");
        assertThrows(IllegalStateException.class, guard::validate);
    }

    @Test
    @DisplayName("真实随机 token ⇒ 放行")
    void realTokenPasses() {
        assertDoesNotThrow(() -> new WorkerTokenGuard(
            envWith("production"),
            "b7f3c1a95d2e8046a1f3c9e7d5b2048ac1e7d3b95f204a6c8e1d7b3f5a9c2e604d"
        ).validate());
    }
}
