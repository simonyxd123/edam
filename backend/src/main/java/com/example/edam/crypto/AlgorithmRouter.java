package com.example.edam.crypto;

import com.example.edam.crypto.gmsm.SM3Util;
import com.example.edam.crypto.gmsm.SM4Util;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 算法路由器（v3.3 W-2.2）
 *
 * 根据配置和密级动态选择国密或国际算法：
 *
 * - 默认：国际算法（AES-256/SHA-256/HMAC-SHA256）
 * - 启用国密：edam.crypto.use-gmsm=true
 * - 密级 L3+ 强制国密（不可绕过）
 *
 * 策略矩阵：
 * | 密级 | 国密优先 | 国密强制 |
 * | L1 | - | - |
 * | L2 | ✓ | - |
 * | L3 | ✓ | ✓ |
 * | L4 | ✓ | ✓ |
 */
@Slf4j
@Component
public class AlgorithmRouter {

    public enum Algorithm {
        AES_256_GCM,    // 国际对称加密
        SM4_GCM,        // 国密对称加密
        HMAC_SHA256,    // 国际 HMAC
        SM3_HMAC,       // 国密 HMAC
        SHA_256,        // 国际摘要
        SM3             // 国密摘要
    }

    @Value("${edam.crypto.use-gmsm:false}")
    private boolean useGmsmDefault;

    /**
     * 根据密级 + 配置选择对称加密算法
     *
     * @param classificationLevel 密级（L1/L2/L3/L4）
     * @return Algorithm
     */
    public Algorithm selectSymmetric(String classificationLevel) {
        if (isGmRequired(classificationLevel)) {
            log.debug("algorithm_select=SM4_GCM level={}", classificationLevel);
            return Algorithm.SM4_GCM;
        }
        if (useGmsmDefault) {
            return Algorithm.SM4_GCM;
        }
        return Algorithm.AES_256_GCM;
    }

    /**
     * 根据密级 + 配置选择 HMAC
     */
    public Algorithm selectHmac(String classificationLevel) {
        if (isGmRequired(classificationLevel)) {
            return Algorithm.SM3_HMAC;
        }
        if (useGmsmDefault) {
            return Algorithm.SM3_HMAC;
        }
        return Algorithm.HMAC_SHA256;
    }

    /**
     * 根据密级 + 配置选择摘要
     */
    public Algorithm selectDigest(String classificationLevel) {
        if (isGmRequired(classificationLevel)) {
            return Algorithm.SM3;
        }
        if (useGmsmDefault) {
            return Algorithm.SM3;
        }
        return Algorithm.SHA_256;
    }

    /**
     * 强制使用国密（用于 L3+ 场景）
     */
    private boolean isGmRequired(String classificationLevel) {
        if (classificationLevel == null) return false;
        switch (classificationLevel.toUpperCase()) {
            case "L3":
            case "L4":
                return true;
            default:
                return false;
        }
    }

    /**
     * 加密（按 Algorithm 自动选择）
     */
    public byte[] encrypt(Algorithm algo, byte[] plaintext, byte[] key, byte[] iv) {
        return switch (algo) {
            case SM4_GCM -> SM4Util.encryptGcm(plaintext, key, iv);
            case AES_256_GCM -> encryptAesGcm(plaintext, key, iv);
            default -> throw new IllegalArgumentException("Unsupported algorithm: " + algo);
        };
    }

    /**
     * 解密
     */
    public byte[] decrypt(Algorithm algo, byte[] ciphertext, byte[] key, byte[] iv) {
        return switch (algo) {
            case SM4_GCM -> SM4Util.decryptGcm(ciphertext, key, iv);
            case AES_256_GCM -> decryptAesGcm(ciphertext, key, iv);
            default -> throw new IllegalArgumentException("Unsupported algorithm: " + algo);
        };
    }

    /**
     * HMAC（按 Algorithm 自动选择）
     */
    public String hmac(Algorithm algo, byte[] key, byte[] data) {
        return switch (algo) {
            case SM3_HMAC -> SM3Util.hmac(key, data);
            case HMAC_SHA256 -> hmacSha256(key, data);
            default -> throw new IllegalArgumentException("Unsupported HMAC: " + algo);
        };
    }

    /**
     * 摘要（按 Algorithm 自动选择）
     */
    public String digest(Algorithm algo, byte[] data) {
        return switch (algo) {
            case SM3 -> SM3Util.digest(data);
            case SHA_256 -> sha256(data);
            default -> throw new IllegalArgumentException("Unsupported digest: " + algo);
        };
    }

    // ---- 国际算法回退 ----

    private byte[] encryptAesGcm(byte[] plaintext, byte[] key, byte[] iv) {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec =
                new javax.crypto.spec.GCMParameterSpec(128, iv);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"), spec);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM encrypt failed", e);
        }
    }

    private byte[] decryptAesGcm(byte[] ciphertext, byte[] key, byte[] iv) {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec =
                new javax.crypto.spec.GCMParameterSpec(128, iv);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"), spec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM decrypt failed", e);
        }
    }

    private String hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sigBytes = mac.doFinal(data);
            return java.util.HexFormat.of().formatHex(sigBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}