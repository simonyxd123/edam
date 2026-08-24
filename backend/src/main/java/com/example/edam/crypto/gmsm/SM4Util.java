package com.example.edam.crypto.gmsm;

import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SM4 国密对称加密工具类（v3.3 W-2.1）
 *
 * 算法：
 * - SM4-CBC（128 bit 分组，PKCS#7 填充）
 * - SM4-GCM（认证加密，128 bit tag）
 *
 * 标准：GM/T 0002-2012《SM4 分组密码算法》
 *
 * 注意：
 * - 当前实现基于 BouncyCastle 1.78（开源过渡方案）
 * - 生产部署应使用通过 GM/T 0028 认证的国密 SDK
 *   （三未信安 / 卫士通商用模块，50-200 万）
 *
 * 用法：
 * ```java
 * byte[] key = SM4Util.generateKey();
 * byte[] iv  = SM4Util.generateIv();
 * byte[] ciphertext = SM4Util.encryptCbc(plaintext, key, iv);
 * byte[] decrypted  = SM4Util.decryptCbc(ciphertext, key, iv);
 * ```
 */
public final class SM4Util {

    private static final int BLOCK_SIZE = 16;  // SM4 块大小 128 bit
    private static final int KEY_SIZE = 16;    // SM4 密钥 128 bit
    private static final int GCM_IV_SIZE = 12; // GCM 推荐 IV 长度 96 bit
    private static final int GCM_TAG_SIZE = 128; // GCM 认证 tag 128 bit

    private static final SecureRandom RNG = new SecureRandom();

    private SM4Util() {}

    /**
     * 生成 16 字节随机密钥
     */
    public static byte[] generateKey() {
        byte[] key = new byte[KEY_SIZE];
        RNG.nextBytes(key);
        return key;
    }

    /**
     * 生成 16 字节随机 IV
     */
    public static byte[] generateIv() {
        byte[] iv = new byte[BLOCK_SIZE];
        RNG.nextBytes(iv);
        return iv;
    }

    /**
     * 生成 12 字节 GCM IV（推荐长度）
     */
    public static byte[] generateGcmIv() {
        byte[] iv = new byte[GCM_IV_SIZE];
        RNG.nextBytes(iv);
        return iv;
    }

    /**
     * SM4-CBC 加密
     *
     * @param plaintext 明文
     * @param key       16 字节密钥
     * @param iv        16 字节 IV
     * @return 密文
     */
    public static byte[] encryptCbc(byte[] plaintext, byte[] key, byte[] iv) {
        validateKeyAndIv(key, iv);

        SM4Engine engine = new SM4Engine();
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
            new CBCBlockCipher(engine));
        cipher.init(true, new ParametersWithIV(new KeyParameter(key), iv));

        return process(cipher, plaintext);
    }

    /**
     * SM4-CBC 解密
     */
    public static byte[] decryptCbc(byte[] ciphertext, byte[] key, byte[] iv) {
        validateKeyAndIv(key, iv);

        SM4Engine engine = new SM4Engine();
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
            new CBCBlockCipher(engine));
        cipher.init(false, new ParametersWithIV(new KeyParameter(key), iv));

        return process(cipher, ciphertext);
    }

    /**
     * SM4-GCM 加密（认证加密，输出 IV + ciphertext + tag）
     *
     * 注：BouncyCastle 的 SM4-GCM 通过 SM4Engine + GCMMode 实现
     */
    public static byte[] encryptGcm(byte[] plaintext, byte[] key, byte[] iv) {
        validateKeyAndIv(key, iv);

        try {
            // BC 中 SM4-GCM 通过 JCE Provider 调用
            Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", "BC");
            SecretKey secretKey = new SecretKeySpec(key, "SM4");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_SIZE, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new RuntimeException("SM4-GCM encrypt failed", e);
        }
    }

    /**
     * SM4-GCM 解密
     */
    public static byte[] decryptGcm(byte[] ciphertext, byte[] key, byte[] iv) {
        validateKeyAndIv(key, iv);

        try {
            Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", "BC");
            SecretKey secretKey = new SecretKeySpec(key, "SM4");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_SIZE, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("SM4-GCM decrypt failed", e);
        }
    }

    /**
     * ECB 模式（不推荐，仅供测试）
     */
    public static byte[] encryptEcb(byte[] plaintext, byte[] key) {
        if (key.length != KEY_SIZE) {
            throw new IllegalArgumentException("SM4 key must be 16 bytes");
        }
        SM4Engine engine = new SM4Engine();
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(engine);
        cipher.init(true, new KeyParameter(key));
        return process(cipher, plaintext);
    }

    private static byte[] process(PaddedBufferedBlockCipher cipher, byte[] input) {
        byte[] output = new byte[cipher.getOutputSize(input.length)];
        int len1 = cipher.processBytes(input, 0, input.length, output, 0);
        int len2;
        try {
            len2 = cipher.doFinal(output, len1);
        } catch (org.bouncycastle.crypto.InvalidCipherTextException e) {
            throw new RuntimeException("SM4 cipher finalisation failed", e);
        }
        return Arrays.copyOf(output, len1 + len2);
    }

    private static void validateKeyAndIv(byte[] key, byte[] iv) {
        if (key == null || key.length != KEY_SIZE) {
            throw new IllegalArgumentException("SM4 key must be 16 bytes, got " +
                (key == null ? "null" : key.length));
        }
        if (iv == null || iv.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("SM4 IV must be 16 bytes");
        }
    }

    /**
     * 16 字节 hex 字符串转 byte[]
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * byte[] 转 16 字节 hex 字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}