package com.example.edam.security.sso;

import java.util.Map;

/**
 * SSO Provider 接口（v3.3 W-4.1）
 *
 * 抽象不同协议（SAML 2.0 / OIDC）的实现
 */
public interface SsoProvider {

    /** Provider 唯一 ID */
    String getId();

    /** 显示名称 */
    String getDisplayName();

    /** 协议类型 */
    String getProtocol();

    /**
     * 生成授权重定向 URL（SP → IdP）
     */
    String getAuthorizationRedirectUrl();

    /**
     * 解析 IdP 回调，提取用户信息
     */
    SsoUserInfo resolveUserInfo(SsoCallbackPayload payload);

    /**
     * 生成 SP Metadata XML（SAML 专用）
     */
    default String getSpMetadataXml() {
        return "";
    }

    /**
     * Provider 配置信息（前端展示用）
     */
    Map<String, Object> getConfig();
}