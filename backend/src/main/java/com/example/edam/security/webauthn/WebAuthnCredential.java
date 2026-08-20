package com.example.edam.security.webauthn;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * WebAuthn 凭据表（v3.3 W-5.2）
 *
 * 存储用户的 FIDO2 凭据
 */
@Data
@TableName("webauthn_credential")
public class WebAuthnCredential {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID */
    private Long userId;

    /** 凭据 ID（base64url 编码）*/
    private String credentialId;

    /** 凭据公钥（COSE 编码，base64url）*/
    private String publicKey;

    /** 签名计数器（每次认证 +1）*/
    private Long counter;

    /** AAGUID（认证器 GUID）*/
    private String aaguid;

    /** 凭据类型：platform / cross-platform */
    private String credentialType;

    /** 用户验证方式：face / touch / pin / none */
    private String userVerification;

    /** 备份状态 */
    private Boolean backupEligible;
    private Boolean backupState;

    /** 凭据名称（用户自定义，如"工作笔记本指纹"）*/
    private String name;

    /** 最后使用时间 */
    private LocalDateTime lastUsedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 是否撤销 */
    private Boolean revoked;

    /** 撤销时间 */
    private LocalDateTime revokedAt;

    /** 撤销原因 */
    private String revokedReason;
}