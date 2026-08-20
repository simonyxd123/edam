package com.example.edam.security.sso;

import lombok.Data;

/**
 * SSO 回调载荷（v3.3 W-4.1）
 *
 * 包含协议类型 + Provider ID + 原始 payload
 */
@Data
public class SsoCallbackPayload {
    private String protocol;          // saml2 / oidc
    private String providerId;         // keycloak / okta / azure-ad
    private String rawPayload;          // SAML Response / OIDC Code
    private String state;               // CSRF token
    private Map extra;                  // 协议特定字段

    public static class Map {
    }
}