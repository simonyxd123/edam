package com.example.edam.security.webauthn;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * WebAuthn 配置属性（v3.3 W-5.1）
 *
 * FIDO2 / WebAuthn 无密码登录
 */
@Data
@ConfigurationProperties(prefix = "edam.webauthn")
public class WebAuthnProperties {

    /** 是否启用 WebAuthn 登录 */
    private boolean enabled = true;

    /** RP（依赖方）信息 */
    private String rpId = "example.com";
    private String rpName = "EDAM";
    private String rpOrigin = "https://app.example.com";

    /** 挑战存储（Redis）TTL（秒）*/
    private int challengeTtlSeconds = 300;

    /** 凭据选项 */
    private AuthenticatorSelection authenticatorSelection = new AuthenticatorSelection();

    /** Attestation 偏好 */
    private String attestation = "none";

    /** 公共密钥算法偏好（按优先级）*/
    private List<Long> pubKeyCredParams = List.of(-7L, -257L);  // ES256, RS256

    /** 用户验证偏好 */
    private String userVerification = "preferred";

    /** 凭据超时（毫秒）*/
    private long timeout = 60000L;

    @Data
    public static class AuthenticatorSelection {
        /** 是否要求 resident key */
        private boolean residentKey = true;

        /** 用户验证要求 */
        private String userVerification = "preferred";

        /** 平台/跨平台选择 */
        private String authenticatorAttachment = "platform";
    }
}