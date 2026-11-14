package com.example.edam.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClientIpResolver} 单测。
 *
 * <p>这个类决定审计日志里记的是"谁的地址"，所以两条线都要盯住：
 * <ul>
 *   <li><b>要准</b>：反代后面必须是用户的真实地址，不是 127.0.0.1 / 网关 Pod IP；</li>
 *   <li><b>要防伪造</b>：客户端可以随便填 {@code X-Forwarded-For}，
 *       审计里的 IP 不能被它写歪 —— 否则审计就失去了取证价值。</li>
 * </ul>
 */
class ClientIpResolverTest {

    private MockHttpServletRequest req(String remoteAddr, String xff, String xri) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/videos");
        r.setRemoteAddr(remoteAddr);
        if (xff != null) {
            r.addHeader("X-Forwarded-For", xff);
        }
        if (xri != null) {
            r.addHeader("X-Real-IP", xri);
        }
        return r;
    }

    // ==================================================================
    // fail-closed：不受信来源的转发头一律不采信
    // ==================================================================

    @Test
    @DisplayName("未配置受信代理时，伪造的 XFF 一律忽略")
    void ignoresXffWhenNoTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver("");
        assertEquals("10.0.0.5", resolver.resolve(req("10.0.0.5", "1.2.3.4", "5.6.7.8")));
    }

    @Test
    @DisplayName("来自不受信来源时仍取 socket 真实 IP")
    void ignoresXffFromUntrustedSource() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        // 10.99.x.x 不在受信段里 → 伪造的 XFF 无效
        assertEquals("10.99.1.1", resolver.resolve(req("10.99.1.1", "1.2.3.4", "5.6.7.8")));
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("受信代理 + 单个 XFF ⇒ 就是客户端地址")
    void takesSingleForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("203.0.113.7", resolver.resolve(req("10.42.1.5", "203.0.113.7", null)));
    }

    @Test
    @DisplayName("受信代理 + XFF 里带代理地址 ⇒ 跳过代理那一段，取客户端")
    void skipsTrustedProxyInsideForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        // 典型形态：ingress → nginx → 后端，XFF = "<客户端>, <ingress Pod>"
        assertEquals("203.0.113.7",
            resolver.resolve(req("10.42.1.5", "203.0.113.7, 10.42.1.4", null)));
    }

    @Test
    @DisplayName("客户端伪造的 XFF 前缀被忽略（代理追加在末尾，最右边才是真的）")
    void forgedForwardedForPrefixIsIgnored() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        // 客户端自己塞了 "1.2.3.4"，代理追加真实地址 ⇒ 必须取真实地址
        assertEquals("203.0.113.9",
            resolver.resolve(req("10.42.1.5", "1.2.3.4, 203.0.113.9", null)));
    }

    @Test
    @DisplayName("XFF 缺失时用 X-Real-IP 兜底")
    void fallsBackToXRealIp() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("198.51.100.9", resolver.resolve(req("10.42.1.5", null, "198.51.100.9")));
    }

    @Test
    @DisplayName("X-Real-IP 本身是代理地址时也跳过（多级代理）")
    void skipsTrustedProxyInXRealIp() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("10.42.1.5", resolver.resolve(req("10.42.1.5", null, "10.42.1.4")));
    }

    @Test
    @DisplayName("XFF 全是代理地址 ⇒ 退回对端地址")
    void fallsBackToPeerWhenAllAreProxies() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("10.42.1.5",
            resolver.resolve(req("10.42.1.5", "10.42.1.3, 10.42.1.4", null)));
    }

    // ==================================================================
    // 非法输入：不许进库（伪造审计 / 撑爆 VARCHAR(45)）
    // ==================================================================

    @Test
    @DisplayName("超长的伪造 XFF 被丢弃（否则整条审计写不进 VARCHAR(45)）")
    void dropsOversizedForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        String oversized = "9".repeat(500);
        assertEquals("10.42.1.5", resolver.resolve(req("10.42.1.5", oversized, null)));
    }

    @Test
    @DisplayName("非 IP 的伪造值（主机名 / 带端口）被丢弃")
    void dropsNonIpForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("10.42.1.5",
            resolver.resolve(req("10.42.1.5", "evil.example.com", null)));
        assertEquals("10.42.1.5",
            resolver.resolve(req("10.42.1.5", "203.0.113.7:8080", null)));
        assertEquals("10.42.1.5",
            resolver.resolve(req("10.42.1.5", "unknown", null)));
    }

    @Test
    @DisplayName("非法项在链里被跳过，后面的合法项仍然取得到")
    void skipsInvalidEntriesInsideChain() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("203.0.113.7",
            resolver.resolve(req("10.42.1.5", "203.0.113.7, unknown, 10.42.1.4", null)));
    }

    @Test
    @DisplayName("对端地址本身不合法 ⇒ unknown")
    void unknownWhenPeerIsGarbage() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("unknown", resolver.resolve(req("not-an-ip", "203.0.113.7", null)));
        assertEquals("unknown", resolver.resolve((HttpServletRequest) null));
    }

    // ==================================================================
    // IPv6
    // ==================================================================

    @Test
    @DisplayName("IPv6 客户端地址能被正确取到")
    void supportsIpv6Client() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("2001:db8::1",
            resolver.resolve(req("10.42.1.5", "2001:db8::1", null)));
    }

    @Test
    @DisplayName("IPv6 也能作为受信代理")
    void supportsIpv6TrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver("::1/128,2001:db8:ffff::/48");
        assertEquals("203.0.113.7", resolver.resolve(req("2001:db8:ffff::9", "203.0.113.7", null)));
        assertEquals("203.0.113.7", resolver.resolve(req("::1", "203.0.113.7", null)));
        // 不在受信段里 ⇒ 头不采信
        assertEquals("2001:db8:aaaa::9",
            resolver.resolve(req("2001:db8:aaaa::9", "203.0.113.7", null)));
    }

    // ==================================================================
    // 名单解析
    // ==================================================================

    @Test
    @DisplayName("单个 IP 也能作为受信代理")
    void supportsSingleIp() {
        ClientIpResolver resolver = new ClientIpResolver("192.168.1.10");
        assertEquals("203.0.113.7", resolver.resolve(req("192.168.1.10", "203.0.113.7", null)));
        assertEquals("192.168.1.11", resolver.resolve(req("192.168.1.11", "203.0.113.7", null)));
    }

    @Test
    @DisplayName("多个受信代理用逗号分隔")
    void supportsMultipleProxies() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16, 192.168.1.10");
        assertEquals("203.0.113.7", resolver.resolve(req("10.42.1.5", "203.0.113.7", null)));
        assertEquals("203.0.113.8", resolver.resolve(req("192.168.1.10", "203.0.113.8", null)));
    }

    @Test
    @DisplayName("名单里写错的东西被跳过，不影响其它项")
    void skipsInvalidTrustedProxyEntry() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16, 不是IP, 10.99.0.0/16");
        assertTrue(resolver.isTrustedProxy("10.42.1.5"));
        assertTrue(resolver.isTrustedProxy("10.99.1.5"));
        assertFalse(resolver.isTrustedProxy("10.43.1.5"));
    }

    @Test
    @DisplayName("开发环境的默认名单（回环）能识别 vite dev server 代理")
    void loopbackIsTrustedByDefaultConfig() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32,::1/128");
        assertEquals("192.168.1.20", resolver.resolve(req("127.0.0.1", "192.168.1.20", null)));
        assertEquals("::1", resolver.resolve(req("::1", null, null)));
    }
}
