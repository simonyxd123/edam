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
        String path = request.getRequestURI();
        String providerId = path.substring("/auth/sso/callback/".length());

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