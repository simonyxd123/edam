package com.example.edam.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SsoRequestPaths} 单测。
 *
 * <p>核心是"剥掉 context-path" —— 项目配了
 * {@code server.servlet.context-path=/api/v1}，而
 * {@code request.getRequestURI()} 返回的是**含** context-path 的路径。
 * 此前直接拿它跟前缀比，SSO 登录 / 回调 / 元数据三条路径全是死的。
 */
class SsoRequestPathsTest {

    private HttpServletRequest req(String uri, String contextPath, String servletPath) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRequestURI()).thenReturn(uri);
        when(r.getContextPath()).thenReturn(contextPath);
        when(r.getServletPath()).thenReturn(servletPath);
        return r;
    }

    @Test
    @DisplayName("带 context-path 时能正确识别并取出 providerId")
    void stripsContextPath() {
        HttpServletRequest r = req("/api/v1/auth/sso/login/keycloak", "/api/v1", "");
        assertEquals("/auth/sso/login/keycloak", SsoRequestPaths.pathWithinContext(r));
        assertEquals("keycloak", SsoRequestPaths.extractAfter(r, SsoRequestPaths.LOGIN_PREFIX));
    }

    @Test
    @DisplayName("getServletPath 有值时优先用它")
    void prefersServletPath() {
        HttpServletRequest r = req("/api/v1/auth/sso/callback/oidc-keycloak", "/api/v1",
            "/auth/sso/callback/oidc-keycloak");
        assertEquals("oidc-keycloak",
            SsoRequestPaths.extractAfter(r, SsoRequestPaths.CALLBACK_PREFIX));
    }

    @Test
    @DisplayName("无 context-path 时原样返回")
    void worksWithoutContextPath() {
        HttpServletRequest r = req("/auth/sso/login/keycloak", "", "");
        assertEquals("keycloak", SsoRequestPaths.extractAfter(r, SsoRequestPaths.LOGIN_PREFIX));
    }

    @Test
    @DisplayName("前缀不匹配时返回 null（不误判）")
    void returnsNullOnMismatch() {
        HttpServletRequest r = req("/api/v1/videos", "/api/v1", "");
        assertNull(SsoRequestPaths.extractAfter(r, SsoRequestPaths.LOGIN_PREFIX));
    }

    @Test
    @DisplayName("providerId 里含斜杠时视为非法（防路径穿越）")
    void rejectsSlashInProviderId() {
        HttpServletRequest r = req("/api/v1/auth/sso/login/a/b", "/api/v1", "");
        assertNull(SsoRequestPaths.extractAfter(r, SsoRequestPaths.LOGIN_PREFIX));
    }

    @Test
    @DisplayName("三条路径前缀都能正确识别")
    void allPrefixesWork() {
        assertEquals("keycloak", SsoRequestPaths.extractAfter(
            req("/api/v1/auth/sso/login/keycloak", "/api/v1", ""), SsoRequestPaths.LOGIN_PREFIX));
        assertEquals("saml2-adfs", SsoRequestPaths.extractAfter(
            req("/api/v1/auth/sso/callback/saml2-adfs", "/api/v1", ""), SsoRequestPaths.CALLBACK_PREFIX));
        assertEquals("okta", SsoRequestPaths.extractAfter(
            req("/api/v1/auth/sso/metadata/okta", "/api/v1", ""), SsoRequestPaths.METADATA_PREFIX));
    }
}
