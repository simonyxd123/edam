package com.example.edam.security.sso;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSO Provider 注册中心（v3.3 W-4.1）
 *
 * 维护 Provider ID → SsoProvider 实例的映射
 * 启动时根据配置自动注册所有启用的 Provider
 */
@Slf4j
@Component
@Configuration
@EnableConfigurationProperties(SsoProperties.class)
@RequiredArgsConstructor
public class SsoProviderRegistry {

    private final SsoProperties properties;
    private final Saml2ProviderFactory saml2Factory;
    private final OidcProviderFactory oidcFactory;

    private final Map<String, SsoProvider> providers = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (SsoProperties.ProviderConfig config : properties.getProviders()) {
            if (!config.isEnabled()) {
                log.info("sso_provider_disabled provider_id={}", config.getId());
                continue;
            }

            SsoProvider provider;
            try {
                if ("saml2".equalsIgnoreCase(config.getProtocol())) {
                    provider = saml2Factory.create(config);
                } else if ("oidc".equalsIgnoreCase(config.getProtocol())) {
                    provider = oidcFactory.create(config);
                } else {
                    log.warn("sso_protocol_unknown provider_id={} protocol={}",
                        config.getId(), config.getProtocol());
                    continue;
                }
                providers.put(config.getId(), provider);
                log.info("sso_provider_registered provider_id={} protocol={} display_name={}",
                    config.getId(), config.getProtocol(), config.getDisplayName());
            } catch (Exception e) {
                log.error("sso_provider_register_failed provider_id={} error={}",
                    config.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 获取 Provider
     */
    public SsoProvider getProvider(String providerId) {
        return providers.get(providerId);
    }

    /**
     * 获取默认 Provider
     */
    public SsoProvider getDefaultProvider() {
        String defaultId = properties.getDefaultProvider();
        return providers.get(defaultId);
    }

    /**
     * 列出所有 Provider
     */
    public Map<String, SsoProvider> listProviders() {
        return Map.copyOf(providers);
    }
}