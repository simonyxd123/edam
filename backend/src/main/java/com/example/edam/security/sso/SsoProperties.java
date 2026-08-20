package com.example.edam.security.sso;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * SSO 配置属性（v3.3 W-4.1）
 *
 * 支持 SAML 2.0 + OIDC 双协议
 */
@Data
@ConfigurationProperties(prefix = "edam.sso")
public class SsoProperties {

    private boolean enabled = false;

    /** 是否强制 SSO（禁用本地密码登录）*/
    private boolean enforce = false;

    /** 默认 IdP（SP 启动时跳转）*/
    private String defaultProvider = "keycloak";

    /** 信任的 IdP 列表 */
    private List<ProviderConfig> providers = new ArrayList<>();

    /** JIT Provisioning */
    private boolean jitEnabled = true;
    private String defaultDeptCode = "external";
    private String defaultRoleCode = "employee";

    /** 登录后回调 URL */
    private String successRedirect = "/api/v1/auth/me";
    private String failureRedirect = "/login?error=sso_failed";

    @Data
    public static class ProviderConfig {
        /** Provider ID（keycloak / okta / azure-ad）*/
        private String id;

        /** 显示名称 */
        private String displayName;

        /** 协议：saml2 / oidc */
        private String protocol;

        /** 是否启用 */
        private boolean enabled = true;

        // SAML 2.0 配置
        private String entityId;
        private String ssoUrl;          // IdP SSO URL
        private String idpEntityId;     // IdP Entity ID
        private String idpMetadataUrl;  // IdP Metadata URL
        private String spPrivateKey;
        private String spCertificate;

        // OIDC 配置
        private String issuer;          // OIDC Issuer
        private String clientId;
        private String clientSecret;
        private String authorizationUri;
        private String tokenUri;
        private String userinfoUri;
        private String jwksUri;
        private String redirectUri;

        /** 属性映射（IdP user attribute → EDAM user field）*/
        private AttributeMapping attributeMapping = new AttributeMapping();
    }

    @Data
    public static class AttributeMapping {
        /** 用户唯一 ID（必需）*/
        private String userId = "uid";

        /** 工号（EDAM employee_no）*/
        private String employeeNo = "employeeNumber";

        /** 真实姓名 */
        private String realName = "cn";

        /** 邮箱 */
        private String email = "mail";

        /** 部门 */
        private String department = "department";

        /** 角色（多个用逗号分隔）*/
        private String roles = "memberOf";
    }
}