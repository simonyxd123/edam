package com.example.edam.crypto.gmsm;

import org.bouncycastle.crypto.digests.SM3Digest;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * SM3 国密杂凑算法（v3.3 W-2.1）
 *
 * 标准：GM/T 0004-2012《SM3 杂凑算法》
 * 输出长度：256 bit（32 字节）
 *
 * 用途：
 * - JWT 摘要签名（替代 HMAC-SHA256）
 * - 消息完整性校验
 * - 数字签名中的哈希
 *
 * 用法：
 * ```java
 * String hash = SM3Util.digest("Hello, 国密!");
 * String hmac = SM3Util.hmac(key, "message");
 * ```
 */
public final class SM3Util {

    private SM3Util() {}

    /**
     * SM3 摘要
     *
     * @param data 输入数据
     * @return 32 字节 hex 摘要
     */
    public static String digest(byte[] data) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return HexFormat.of().formatHex(out);
    }

    /**
     * SM3 摘要（字符串便捷方法）
     */
    public static String digest(String message) {
        return digest(message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SM3 增量式摘要（适合大文件）
     *
     * @return Digest 用于累计 update，最后 doFinal
     */
    public static SM3Digest newDigest() {
        return new SM3Digest();
    }

    /**
     * SM3-HMAC（基于 SM3 的消息认证码）
     *
     * @param key  密钥（任意长度，内部 pad 到 64 字节）
     * @param data 消息
     * @return 32 字节 hex HMAC
     */
    public static String hmac(byte[] key, byte[] data) {
        SM3Digest digest = new SM3Digest();
        byte[] block = new byte[64];
        int keyLen = Math.min(key.length, 64);
        System.arraycopy(key, 0, block, 0, keyLen);
        if (keyLen < 64) {
            for (int i = keyLen; i < 64; i++) block[i] = 0;
        }

        // ipad
        byte[] iKeyPad = new byte[64];
        for (int i = 0; i < 64; i++) iKeyPad[i] = (byte) (block[i] ^ 0x36);
        digest.update(iKeyPad, 0, iKeyPad.length);
        digest.update(data, 0, data.length);
        byte[] inner = new byte[digest.getDigestSize()];
        digest.doFinal(inner, 0);

        // opad
        byte[] oKeyPad = new byte[64];
        for (int i = 0; i < 64; i++) oKeyPad[i] = (byte) (block[i] ^ 0x5c);
        digest.update(oKeyPad, 0, oKeyPad.length);
        digest.update(inner, 0, inner.length);
        byte[] outer = new byte[digest.getDigestSize()];
        digest.doFinal(outer, 0);

        return HexFormat.of().formatHex(outer);
    }

    /**
     * SM3-HMAC 便捷方法（字符串输入）
     */
    public static String hmac(String key, String message) {
        return hmac(key.getBytes(StandardCharsets.UTF_8),
                    message.getBytes(StandardCharsets.UTF_8));
    }
}