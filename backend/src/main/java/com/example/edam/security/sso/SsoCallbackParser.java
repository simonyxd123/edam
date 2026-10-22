package com.example.edam.security.sso;

import jakarta.servlet.http.HttpServletRequest;

/**
 * SSO 回调解析器（v3.3 W-4.1）
 *
 * 简化版本：解析 SAML Response 与 OIDC Code
 * 实际生产应使用 Spring Security SAML2 + OAuth2 Client
 */
public class SsoCallbackParser {

    public static SsoCallbackPayload parse(HttpServletRequest request) {
        // 用 context-path 之外的路径切 providerId。
        // 原实现直接 substring(getRequestURI())，在 context-path=/api/v1 下
        // 会切出 "api/v1/auth/sso/callback/xxx" 当 providerId → 查不到 provider。
        String providerId = SsoRequestPaths.extractAfter(request, SsoRequestPaths.CALLBACK_PREFIX);
        if (providerId == null) {
            throw new IllegalArgumentException("无法从请求路径解析 providerId: "
                + SsoRequestPaths.pathWithinContext(request));
        }

        SsoCallbackPayload payload = new SsoCallbackPayload();
        payload.setProviderId(providerId);

        if (providerId.startsWith("saml2-")) {
            payload.setProtocol("saml2");
            payload.setRawPayload(request.getParameter("SAMLResponse"));
        } else {
            payload.setProtocol("oidc");
            payload.setRawPayload(request.getParameter("code"));
        }
        payload.setState(request.getParameter("state"));
        return payload;
    }
}