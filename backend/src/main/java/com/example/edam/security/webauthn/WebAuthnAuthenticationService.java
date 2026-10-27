package com.example.edam.security.webauthn;

import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.WebAuthnCredentialRepository;
import com.example.edam.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.Signature;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * WebAuthn 认证服务（v3.3 W-5.3）
 *
 * 验证 assertion + 登录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebAuthnAuthenticationService {

    private final WebAuthnProperties properties;
    private final WebAuthnCredentialRepository credentialRepository;
    private final SysUserRepository userRepository;

    /**
     * 验证 assertion 并完成登录
     */
    public SysUser verifyAssertionAndLogin(
            String employeeNo,
            WebAuthnController.CompleteLoginRequest request) {

        // 1. 查找用户与凭据
        SysUser user = userRepository.findByEmployeeNo(employeeNo);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        Optional<WebAuthnCredential> credOpt = credentialRepository
            .findByCredentialId(request.getCredentialId());
        if (credOpt.isEmpty()) {
            throw new IllegalArgumentException("凭据不存在");
        }
        WebAuthnCredential credential = credOpt.get();

        // 2. 验证凭据归属
        if (!credential.getUserId().equals(user.getId())) {
            throw new IllegalArgumentException("凭据与用户不匹配");
        }

        // 3. 验证 clientDataJSON
        verifyClientDataJsonForLogin(request.getClientDataJSON(),
            request.getChallenge());

        // 4. 验证 assertion 签名
        try {
            verifyAssertionSignature(
                credential.getPublicKey(),
                request.getAuthenticatorData(),
                request.getClientDataJSON(),
                request.getSignature()
            );
        } catch (Exception e) {
            log.error("webauthn_assertion_verify_failed credential_id={} error={}",
                credential.getCredentialId(), e.getMessage());
            throw new IllegalArgumentException("签名验证失败", e);
        }

        // 5. 更新 counter（防重放）
        long newCounter = credential.getCounter() + 1;
        credentialRepository.updateCounter(credential.getId(), newCounter, TimeUtil.nowUtcLocalDateTime());

        // 6. 更新用户最后登录时间
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.updateById(user);

        log.info("webauthn_assertion_verified credential_id={} counter={}",
            credential.getCredentialId(), newCounter);
        return user;
    }

    /**
     * 验证 clientDataJSON
     */
    private void verifyClientDataJsonForLogin(String clientDataJsonB64, String expectedChallenge) {
        try {
            String clientDataJson = new String(
                Base64.getUrlDecoder().decode(clientDataJsonB64));
            if (!clientDataJson.contains(expectedChallenge)) {
                throw new IllegalArgumentException("Challenge 不匹配");
            }
            if (!clientDataJson.contains("webauthn.get")) {
                throw new IllegalArgumentException("Type 错误（应为 webauthn.get）");
            }
            if (!clientDataJson.contains(properties.getRpOrigin())) {
                throw new IllegalArgumentException("Origin 不匹配");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("clientDataJSON 验证失败", e);
        }
    }

    /**
     * 验证 assertion 签名
     *
     * 注：完整实现应：
     * 1. 解析 authenticatorData（CBOR）
     * 2. 验证 rpIdHash = SHA-256(rpId)
     * 3. 检查 flags（UP=1, UV=1）
     * 4. 验证 signCount ≥ storedCounter
     * 5. 用 publicKey 验证 signature over (authenticatorData + SHA-256(clientDataJSON))
     */
    private void verifyAssertionSignature(String publicKeyB64, String authenticatorDataB64,
                                            String clientDataJSONB64, String signatureB64) {
        // 安全关键：WebAuthn assertion 的签名验证是"无密码登录"的全部根基。
        //
        // 原实现只做 base64 解码，然后打一行
        // "assertion_verify skipped (simplified impl)" 就返回成功 ——
        // 配套的注册侧 extractPublicKey 也只是把 attestation 做 SHA-256
        // 当公钥。于是注册不验 attestation、登录不验 signature，
        // 攻击者只要知道工号 + credentialId 就能登录任意用户。
        //
        // 正确实现需要 webauthn4j（解析 COSE 公钥 / 校验 rpIdHash /
        // flags / signCount / ES256 签名）。
        // 在那之前必须 **fail-closed**：验不过就拒绝登录。
        throw new UnsupportedOperationException(
            "WebAuthn assertion 签名验证未实现，已拒绝登录以避免绕过密码。"
                + "请接入 webauthn4j-spring-security-core 后再启用 WebAuthn 登录。"
        );
    }
}