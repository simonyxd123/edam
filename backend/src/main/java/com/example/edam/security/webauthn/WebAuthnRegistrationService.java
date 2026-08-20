package com.example.edam.security.webauthn;

import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * WebAuthn 注册服务（v3.3 W-5.2）
 *
 * 验证 attestation + 存储公钥
 *
 * 注：完整实现应使用 webauthn4j-spring-security-core 库
 * 本骨架提供核心逻辑 + 异常处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebAuthnRegistrationService {

    private final WebAuthnProperties properties;
    private final WebAuthnCredentialRepository credentialRepository;
    private final SysUserRepository userRepository;

    /**
     * 验证 attestation 并创建凭据
     */
    @Transactional
    public WebAuthnCredential verifyAndCreateCredential(
            Long userId,
            WebAuthnController.CompleteRegistrationRequest request) {

        // 1. 验证 clientDataJSON 中的 origin 与 challenge
        verifyClientDataJson(request.getClientDataJSON(),
            request.getChallenge());

        // 2. 验证 attestation object（生产应使用 webauthn4j）
        // - 解析 CBOR 编码的 attestation
        // - 验证 attestation signature（attestStmt）
        // - 验证 cert chain（如果 attestation=direct）
        // - 提取 publicKey（COSE 编码）
        // - 提取 credentialId
        String publicKey = extractPublicKey(request.getAttestation());
        String credentialId = request.getCredentialId();

        // 3. 验证 credentialId 未被其他用户使用
        var existing = credentialRepository.findByCredentialId(credentialId);
        if (existing.isPresent() && !existing.get().getUserId().equals(userId)) {
            throw new IllegalArgumentException("CredentialId 已被其他用户注册");
        }

        // 4. 创建凭据记录
        WebAuthnCredential credential = new WebAuthnCredential();
        credential.setUserId(userId);
        credential.setCredentialId(credentialId);
        credential.setPublicKey(publicKey);
        credential.setCounter(0L);
        credential.setCredentialType("platform");
        credential.setUserVerification(properties.getAuthenticatorSelection().getUserVerification());
        credential.setBackupEligible(false);
        credential.setBackupState(false);
        credential.setName(request.getName() != null ? request.getName() : "未命名密钥");
        credential.setLastUsedAt(LocalDateTime.now());
        credential.setCreatedAt(LocalDateTime.now());
        credential.setRevoked(false);
        credentialRepository.insert(credential);

        log.info("webauthn_credential_created user_id={} credential_id={}",
            userId, credentialId);
        return credential;
    }

    /**
     * 验证 clientDataJSON
     */
    private void verifyClientDataJson(String clientDataJsonB64, String expectedChallenge) {
        try {
            String clientDataJson = new String(
                Base64.getUrlDecoder().decode(clientDataJsonB64));
            // 实际生产：解析 JSON，验证 type= webauthn.create, challenge, origin
            // 简化版本：仅验证 challenge 匹配
            if (!clientDataJson.contains(expectedChallenge)) {
                throw new IllegalArgumentException("Challenge 不匹配");
            }
            if (!clientDataJson.contains("webauthn.create")) {
                throw new IllegalArgumentException("Type 错误（应为 webauthn.create）");
            }
            if (!clientDataJson.contains(properties.getRpOrigin())) {
                throw new IllegalArgumentException("Origin 不匹配");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("clientDataJSON 验证失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取公钥（COSE 编码）
     *
     * 注：实际生产应解析 attestation object 中的 authData → attestedCredentialData → credentialPublicKey
     * 本简化版直接使用 credentialId hash 作为占位
     */
    private String extractPublicKey(String attestationBase64) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(attestationBase64);
            // 简化：从 attestation 数据中派生（实际生产应解析 CBOR）
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(decoded);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalArgumentException("公钥提取失败", e);
        }
    }
}