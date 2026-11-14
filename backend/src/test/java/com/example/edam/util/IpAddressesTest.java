package com.example.edam.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IpAddresses} 单测。
 *
 * <p>这个类是审计 IP 落库前的最后一道闸：IP 来自客户端可控的请求头，
 * 不校验就既能伪造审计记录，又能用超长值把审计写入打挂。
 */
class IpAddressesTest {

    // ==================================================================
    // IPv4
    // ==================================================================

    @Test
    @DisplayName("正常 IPv4 解析成 4 字节")
    void parsesIpv4() {
        assertArrayEquals(new byte[]{(byte) 192, 0, 2, 1},
            IpAddresses.toBytes("192.0.2.1"));
        assertArrayEquals(new byte[]{0, 0, 0, 0}, IpAddresses.toBytes("0.0.0.0"));
        assertArrayEquals(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255},
            IpAddresses.toBytes("255.255.255.255"));
    }

    @Test
    @DisplayName("段数不对 / 段值超范围 ⇒ 非法")
    void rejectsMalformedIpv4() {
        assertFalse(IpAddresses.isValid("1.2.3"));
        assertFalse(IpAddresses.isValid("1.2.3.4.5"));
        assertFalse(IpAddresses.isValid("1.2.3.256"));
        assertFalse(IpAddresses.isValid("1.2.3.-1"));
        assertFalse(IpAddresses.isValid("1.2.3.4a"));
        assertFalse(IpAddresses.isValid("1.2.3."));
        assertFalse(IpAddresses.isValid(".1.2.3"));
    }

    @Test
    @DisplayName("前导零视为非法（不同解析器对 010 的解释不一致）")
    void rejectsLeadingZeroes() {
        assertFalse(IpAddresses.isValid("010.0.0.1"));
        assertFalse(IpAddresses.isValid("192.168.001.1"));
    }

    // ==================================================================
    // IPv6
    // ==================================================================

    @Test
    @DisplayName("正常 IPv6 解析成 16 字节")
    void parsesIpv6() {
        assertEquals(16, IpAddresses.toBytes("2001:db8::1").length);
        assertEquals(16, IpAddresses.toBytes("::1").length);
        assertEquals(16, IpAddresses.toBytes("fe80::1").length);
        assertEquals(16, IpAddresses.toBytes("2001:0db8:0000:0000:0000:0000:0000:0001").length);
    }

    @Test
    @DisplayName("IPv4 映射形式也认（::ffff:192.0.2.1）")
    void parsesIpv4MappedIpv6() {
        assertTrue(IpAddresses.isValid("::ffff:192.0.2.1"));
        assertEquals(4, IpAddresses.toBytes("::ffff:192.0.2.1").length);
    }

    @Test
    @DisplayName("组数不对 / :: 出现多次 ⇒ 非法")
    void rejectsMalformedIpv6() {
        assertFalse(IpAddresses.isValid("2001:db8::1::2"));
        assertFalse(IpAddresses.isValid("1:2:3:4:5:6:7"));
        assertFalse(IpAddresses.isValid("1:2:3:4:5:6:7:8:9"));
        assertFalse(IpAddresses.isValid("1:2:3:4:5:6:7:8::"));
        assertFalse(IpAddresses.isValid("12345::1"));
        assertFalse(IpAddresses.isValid("2001:db8::zzzz"));
    }

    @Test
    @DisplayName("带 zone id 的地址不认（zone id 是本机接口名，不是 IP）")
    void rejectsZoneId() {
        assertFalse(IpAddresses.isValid("fe80::1%eth0"));
    }

    // ==================================================================
    // 通用
    // ==================================================================

    @Test
    @DisplayName("主机名不是 IP —— 绝不接受（否则会变成 DNS 查询 / SSRF 跳板）")
    void rejectsHostnames() {
        assertFalse(IpAddresses.isValid("evil.example.com"));
        assertFalse(IpAddresses.isValid("localhost"));
        assertFalse(IpAddresses.isValid("0x7f.0.0.1"));
        assertFalse(IpAddresses.isValid("2130706433"));
    }

    @Test
    @DisplayName("空值 / null ⇒ 非法")
    void rejectsNullAndBlank() {
        assertFalse(IpAddresses.isValid(null));
        assertFalse(IpAddresses.isValid(""));
        assertFalse(IpAddresses.isValid("   "));
        assertNull(IpAddresses.toBytes(null));
    }

    @Test
    @DisplayName("超过 45 字符（VARCHAR(45) 上限）⇒ 非法，不许进库")
    void rejectsTooLong() {
        // 45 字符是列的上限；超一字符的伪造值直接拒掉 ——
        // 否则 MySQL 报 "Data too long" ⇒ 整条审计记录写不进去
        assertFalse(IpAddresses.isValid("9".repeat(46)));
        assertFalse(IpAddresses.isValid("1".repeat(45)));
    }

    @Test
    @DisplayName("normalize：去空白后原样返回，非法返回 null")
    void normalizes() {
        assertEquals("192.0.2.1", IpAddresses.normalize("  192.0.2.1 "));
        assertNull(IpAddresses.normalize("  nope "));
        assertNull(IpAddresses.normalize(null));
    }
}
