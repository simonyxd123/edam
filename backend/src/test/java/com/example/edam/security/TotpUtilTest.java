package com.example.edam.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TotpUtil} 单测。
 *
 * <p>用 RFC 6238 附录 B 的**官方测试向量**独立验证实现 ——
 * 不能拿自己的 generate() 去验证自己的 verify()，那样测不出算法错误。
 *
 * <p>RFC 6238 给出的是 8 位值，TOTP 的 6 位是它 mod 10^6。
 */
class TotpUtilTest {

    /** RFC 6238 附录 B：secret = ASCII "12345678901234567890" */
    private static final String SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    @DisplayName("RFC 6238 官方测试向量")
    void rfc6238TestVectors() {
        byte[] key = TotpUtil.decodeBase32(SECRET_BASE32);
        assertEquals(20, key.length, "base32 解码后应是 20 字节");

        // RFC 给的 8 位 → 6 位
        assertEquals("287082", TotpUtil.generate(key, 59L / 30));
        assertEquals("081804", TotpUtil.generate(key, 1111111109L / 30));
        assertEquals("050471", TotpUtil.generate(key, 1111111111L / 30));
        assertEquals("005924", TotpUtil.generate(key, 1234567890L / 30));
        assertEquals("279037", TotpUtil.generate(key, 2000000000L / 30));
        assertEquals("353130", TotpUtil.generate(key, 20000000000L / 30));
    }

    @Test
    @DisplayName("verify 接受正确验证码")
    void verifyAcceptsCorrectCode() {
        byte[] key = TotpUtil.decodeBase32(SECRET_BASE32);
        // 用当前时间步算出验证码，再验证 —— 这里 verify 内部用的也是当前时间步
        long step = System.currentTimeMillis() / 1000L / 30;
        String code = TotpUtil.generate(key, step);
        assertTrue(TotpUtil.verify(SECRET_BASE32, code), "刚生成的验证码应通过校验");
    }

    @Test
    @DisplayName("verify 拒绝错误验证码")
    void verifyRejectsWrongCode() {
        byte[] key = TotpUtil.decodeBase32(SECRET_BASE32);
        long step = System.currentTimeMillis() / 1000L / 30;
        String code = TotpUtil.generate(key, step);
        // 改一位
        char[] chars = code.toCharArray();
        chars[5] = (chars[5] == '9') ? '0' : (char) (chars[5] + 1);
        String wrong = new String(chars);
        assertNotEquals(code, wrong);
        assertFalse(TotpUtil.verify(SECRET_BASE32, wrong), "错误验证码应被拒绝");
    }

    @Test
    @DisplayName("secret 缺失 / 格式非法时 fail-closed")
    void verifyFailsClosedOnBadInput() {
        assertFalse(TotpUtil.verify(null, "123456"), "secret 为 null 必须拒绝");
        assertFalse(TotpUtil.verify("", "123456"), "secret 为空必须拒绝");
        assertFalse(TotpUtil.verify("   ", "123456"), "secret 空白必须拒绝");
        assertFalse(TotpUtil.verify(SECRET_BASE32, null), "验证码为 null 必须拒绝");
        assertFalse(TotpUtil.verify(SECRET_BASE32, "12345"), "位数不对必须拒绝");
        assertFalse(TotpUtil.verify(SECRET_BASE32, "abcdef"), "非数字必须拒绝");
        assertFalse(TotpUtil.verify("!!!not-base32!!!", "123456"), "非法 base32 必须拒绝");
    }

    @Test
    @DisplayName("base32 编解码往返一致")
    void base32RoundTrip() {
        byte[] raw = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        String encoded = TotpUtil.encodeBase32(raw);
        assertEquals(raw.length, TotpUtil.decodeBase32(encoded).length);
        for (int i = 0; i < raw.length; i++) {
            assertEquals(raw[i], TotpUtil.decodeBase32(encoded)[i]);
        }
    }

    @Test
    @DisplayName("generateSecret 产出可用的 base32 密钥")
    void generatedSecretIsUsable() {
        String secret = TotpUtil.generateSecret();
        byte[] key = TotpUtil.decodeBase32(secret);
        assertTrue(key.length >= 20, "密钥至少 20 字节");
        long step = System.currentTimeMillis() / 1000L / 30;
        assertTrue(TotpUtil.verify(secret, TotpUtil.generate(key, step)),
            "生成的密钥应能通过自己的校验");
    }
}
