package com.example.edam.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * RFC 6238 TOTP（RFC 4226 HOTP 的时间版）实现。
 *
 * <p>为什么自己实现而不引依赖：本项目此前对 MFA 验证码**只校验格式**
 * （6 位数字），从不比对 {@code mfaSecret} —— 启用了 MFA 的账号
 * 只要知道密码就能登录。这违反等保对"双因素认证"的要求。
 *
 * <p>算法（标准参数，可与 Google Authenticator / Authy 互通）：
 * <ul>
 *   <li>HMAC-SHA1</li>
 *   <li>6 位数字</li>
 *   <li>30 秒时间步</li>
 *   <li>接受当前步 ±1（共 3 个窗口，容忍客户端时钟偏差 ±30s）</li>
 * </ul>
 *
 * <p>密钥按 RFC 3548 Base32 编码存储（不带填充）。
 */
public final class TotpUtil {

    private static final String ALGO = "HmacSHA1";
    private static final int DIGITS = 6;
    private static final long TIME_STEP_SECONDS = 30L;
    /** 允许的时间步偏移：+1 表示允许上一个窗口，-1 表示下一个 */
    private static final int WINDOW = 1;
    private static final Base64.Decoder B32_DECODER = Base64.getDecoder();

    private TotpUtil() {
    }

    /**
     * 校验 TOTP 验证码。
     *
     * @param secret Base32 编码的密钥（可含 {@code =} 填充与空白）
     * @param code   用户输入的验证码
     * @return 是否通过；secret 缺失 / 格式非法一律返回 false（fail-closed）
     */
    public static boolean verify(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null) {
            return false;
        }
        String normalized = code.trim();
        if (!normalized.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        byte[] key;
        try {
            key = decodeBase32(secret);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (key.length == 0) {
            return false;
        }
        long currentStep = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            String expected = generate(key, currentStep + offset);
            // 常量时间比较，防 timing 旁路
            if (MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    normalized.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成某个时间步的 TOTP 验证码（供测试 / 绑定二维码用）。
     */
    public static String generate(byte[] key, long timeStep) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(key, ALGO));
            // 8 字节大端时间计数器
            byte[] counter = ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array();
            byte[] hash = mac.doFinal(counter);
            // 动态截断（RFC 4226 §5.4）
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 生成失败", e);
        }
    }

    /**
     * 生成一个新密钥（Base32，无填充）。
     */
    public static String generateSecret() {
        byte[] buf = new byte[20];
        new java.security.SecureRandom().nextBytes(buf);
        return encodeBase32(buf);
    }

    /**
     * Base32 解码（RFC 4648，去掉 = 填充，忽略空白与非法字符前的截断）。
     */
    static byte[] decodeBase32(String input) {
        String cleaned = input.replace("=", "").replaceAll("\\s", "").toUpperCase();
        if (cleaned.isEmpty()) {
            return new byte[0];
        }
        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int outIdx = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            int value = ALPHABET.indexOf(cleaned.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("非法 Base32 字符: " + cleaned.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[outIdx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        byte[] result = new byte[outIdx];
        System.arraycopy(out, 0, result, 0, outIdx);
        return result;
    }

    /**
     * Base32 编码（无填充）。
     */
    static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
}
