package com.example.edam.security.sso;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAML 2.0 Provider 工厂（v3.3 W-4.2）
 *
 * 生产实现应使用 Spring Security SAML2 Service Provider：
 *   - org.springframework.security:spring-security-saml2-service-provider
 *   - 配置 RelyingPartyRegistration
 *
 * 本类为简化骨架，生产应替换为完整 SAML2 实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Saml2ProviderFactory {

    /**
     * 创建 SAML 2.0 Provider
     */
    public SsoProvider create(SsoProperties.ProviderConfig config) {
        log.info("saml2_provider_create provider_id={} entity_id={} sso_url={}",
            config.getId(), config.getEntityId(), config.getSsoUrl());

        return new Saml2Provider(config);
    }

    /**
     * SAML 2.0 Provider 实现
     */
    static class Saml2Provider implements SsoProvider {
        private final SsoProperties.ProviderConfig config;

        Saml2Provider(SsoProperties.ProviderConfig config) {
            this.config = config;
        }

        @Override
        public String getId() { return config.getId(); }

        @Override
        public String getDisplayName() { return config.getDisplayName(); }

        @Override
        public String getProtocol() { return "saml2"; }

        @Override
        public String getAuthorizationRedirectUrl() {
            // SAML2 AuthnRequest → 重定向到 IdP SSO URL
            StringBuilder url = new StringBuilder(config.getSsoUrl());
            url.append("?SAMLRequest=").append(generateSamlRequest());
            url.append("&RelayState=").append(config.getId());
            return url.toString();
        }

        @Override
        public SsoUserInfo resolveUserInfo(SsoCallbackPayload payload) {
            // 安全关键：SAML Response 必须验签 + 校验 audience + 校验
            // InResponseTo，才能提取出可信身份。
            //
            // 原实现直接返回一个**硬编码身份**（saml2_<id>_user / SSO_USER），
            // 意味着任何 POST /auth/sso/callback/saml2-* 带任意 SAMLResponse
            // 都能走到 JIT 建号 + 签发合法 JWT —— 配了 IdP 的环境等于没有认证。
            //
            // 本实现尚未接入 spring-security-saml2-service-provider，
            // 因此**fail-closed**：宁可登录失败，也不能发一个假的身份。
            // 正确做法见 CHANGELOG / modify/ 下的 SAML 接入说明。
            throw new UnsupportedOperationException(
                "SAML2 身份解析未实现（缺少 SAML Response 验签），已拒绝登录以避免绕过认证。"
                    + "请接入 spring-security-saml2-service-provider 后再启用 SAML2 provider。"
            );
        }

        @Override
        public String getSpMetadataXml() {
            // SP Metadata XML（IdP 用此注册 SP）
            return """
                <?xml version="1.0" encoding="UTF-8"?>
                <EntityDescriptor xmlns="urn:oasis:names:tc:SAML:2.0:metadata"
                                  entityID="%s">
                  <SPSSODescriptor AuthnRequestsSigned="true"
                                   WantAssertionsSigned="true"
                                   protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</NameIDFormat>
                    <AssertionConsumerService
                        Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                        Location="https://api.example.com/auth/sso/callback/%s"
                        index="0"
                        isDefault="true"/>
                  </SPSSODescriptor>
                </EntityDescriptor>
                """.formatted(config.getEntityId(), config.getId());
        }

        @Override
        public Map<String, Object> getConfig() {
            return Map.of(
                "protocol", "saml2",
                "entity_id", config.getEntityId(),
                "sso_url", config.getSsoUrl(),
                "metadata_url", "/auth/sso/metadata/" + config.getId()
            );
        }

        private String generateSamlRequest() {
            // 实际生产：构造 SAML AuthnRequest XML + Base64
            return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\">" +
                    "<saml:Issuer>" + config.getEntityId() + "</saml:Issuer>" +
                    "</samlp:AuthnRequest>").getBytes());
        }
    }
}