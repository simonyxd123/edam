package com.example.edam.security.sso;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.example.edam.security.sso.SsoUserProvisioning.SsoLoginResult;
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
    private final SsoStateStore stateStore;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        // 路径必须去掉 context-path 再匹配。
        // 原实现用 request.getRequestURI()（含 context-path，如
        // "/api/v1/auth/sso/login/xxx"）跟前缀 "/auth/sso/login/" 比较 ——
        // 永远不匹配 → SSO 登录 / 回调 / 元数据三条路径全是死的。
        String loginProvider = SsoRequestPaths.extractAfter(request, SsoRequestPaths.LOGIN_PREFIX);
        if (loginProvider != null) {
            handleSsoLogin(response, loginProvider);
            return;
        }

        // 2. 处理 SSO 回调（IdP → EDAM）
        if (SsoRequestPaths.extractAfter(request, SsoRequestPaths.CALLBACK_PREFIX) != null) {
            handleSsoCallback(request, response);
            return;
        }

        // 3. 处理 SSO 元数据下载
        String metadataProvider = SsoRequestPaths.extractAfter(request, SsoRequestPaths.METADATA_PREFIX);
        if (metadataProvider != null) {
            handleMetadata(response, metadataProvider);
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

            // 5. 重定向到前端，**只带一次性登录码**，不带 token。
            //    原实现把 access_token + refresh_token 直接拼进 URL ——
            //    会进浏览器历史、反代 access log、下游 Referer。
            //    现在改成一次性 code，前端用 POST /auth/sso/exchange 换 token。
            String loginCode = java.util.UUID.randomUUID().toString()
                .replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "");
            stateStore.saveLoginCode(loginCode, result.getAccessToken(), result.getRefreshToken());

            String redirectUrl = ssoProperties.getSuccessRedirect() +
                (ssoProperties.getSuccessRedirect().contains("?") ? "&" : "?") +
                "code=" + java.net.URLEncoder.encode(loginCode, java.nio.charset.StandardCharsets.UTF_8);
            response.sendRedirect(redirectUrl);

        } catch (Exception e) {
            log.error("sso_login_failed", e);
            // 不把异常 message 拼进重定向 URL —— 里面可能含 IdP 地址、
            // token 片段等内部信息；前端只需要知道"失败了"
            String redirect = ssoProperties.getFailureRedirect();
            response.sendRedirect(redirect.contains("?") ? redirect + "&reason=sso_failed" : redirect + "?reason=sso_failed");
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