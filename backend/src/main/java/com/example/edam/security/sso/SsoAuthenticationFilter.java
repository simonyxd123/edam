package com.example.edam.security.sso;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SSO 认证过滤器（v3.3 W-4.1）
 *
 * 处理 SSO 回调 + Token 桥接
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoAuthenticationFilter extends OncePerRequestFilter {

    private final SsoProperties ssoProperties;
    private final SsoProviderRegistry providerRegistry;
    private final SsoUserProvisioning provisioning;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // 1. 处理 SSO 启动入口（重定向到 IdP）
        if (path.startsWith("/auth/sso/login/")) {
            String providerId = path.substring("/auth/sso/login/".length());
            handleSsoLogin(response, providerId);
            return;
        }

        // 2. 处理 SSO 回调（IdP → EDAM）
        if (path.startsWith("/auth/sso/callback/")) {
            handleSsoCallback(request, response);
            return;
        }

        // 3. 处理 SSO 元数据下载
        if (path.startsWith("/auth/sso/metadata/")) {
            String providerId = path.substring("/auth/sso/metadata/".length());
            handleMetadata(response, providerId);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void handleSsoLogin(HttpServletResponse response, String providerId) throws IOException {
        SsoProvider provider = providerRegistry.getProvider(providerId);
        if (provider == null) {
            log.warn("sso_provider_not_found provider_id={}", providerId);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String redirectUrl = provider.getAuthorizationRedirectUrl();
        log.info("sso_login_redirect provider_id={} url={}", providerId, redirectUrl);
        response.sendRedirect(redirectUrl);
    }

    private void handleSsoCallback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 1. 解析 IdP 响应（SAML Response / OIDC Code）
            SsoCallbackPayload payload = SsoCallbackParser.parse(request);

            // 2. 通过 provider 解析用户信息
            SsoProvider provider = providerRegistry.getProvider(payload.getProviderId());
            if (provider == null) {
                throw new IllegalArgumentException("Unknown provider: " + payload.getProviderId());
            }
            SsoUserInfo userInfo = provider.resolveUserInfo(payload);

            // 3. JIT Provisioning（自动开通或禁用账号）
            if (ssoProperties.isJitEnabled()) {
                provisioning.provision(userInfo);
            }

            // 4. 创建 EDAM Session + JWT Token
            SsoLoginResult result = provisioning.createSession(userInfo);

            log.info("sso_login_success user_id={} employee_no={} provider={}",
                userInfo.getUserId(), userInfo.getEmployeeNo(), payload.getProviderId());

            // 5. 重定向到前端（含 access_token）
            String redirectUrl = ssoProperties.getSuccessRedirect() +
                "?token=" + result.getAccessToken() +
                "&refresh_token=" + result.getRefreshToken();
            response.sendRedirect(redirectUrl);

        } catch (Exception e) {
            log.error("sso_login_failed", e);
            response.sendRedirect(ssoProperties.getFailureRedirect() +
                "&reason=" + e.getMessage());
        }
    }

    private void handleMetadata(HttpServletResponse response, String providerId) throws IOException {
        SsoProvider provider = providerRegistry.getProvider(providerId);
        if (provider == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String metadata = provider.getSpMetadataXml();
        response.setContentType("application/xml");
        response.getWriter().write(metadata);
    }
}