package com.example.edam.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ClientIpResolver} 单测。
 *
 * <p>核心是"只有来自受信代理的请求才采信 X-Forwarded-For" ——
 * 此前无条件采信，攻击者每次换一个伪造 XFF 就能绕过单 IP 限流。
 */
class ClientIpResolverTest {

    private HttpServletRequest req(String remoteAddr, String xff, String xri) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRemoteAddr()).thenReturn(remoteAddr);
        when(r.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(r.getHeader("X-Real-IP")).thenReturn(xri);
        return r;
    }

    @Test
    @DisplayName("未配置受信代理时，伪造的 XFF 一律忽略")
    void ignoresXffWhenNoTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver("");
        // 攻击者伪造 XFF 想绕过限流
        assertEquals("10.0.0.5", resolver.resolve(req("10.0.0.5", "1.2.3.4", "5.6.7.8")));
    }

    @Test
    @DisplayName("来自受信代理时采信 X-Forwarded-For 第一项")
    void trustsXffFromTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("203.0.113.7",
            resolver.resolve(req("10.42.1.5", "203.0.113.7, 10.42.1.4", null)));
    }

    @Test
    @DisplayName("XFF 无第一项时回退到 X-Real-IP")
    void fallsBackToXRealIp() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        assertEquals("198.51.100.9",
            resolver.resolve(req("10.42.1.5", null, "198.51.100.9")));
    }

    @Test
    @DisplayName("不受信来源仍取 socket 真实 IP")
    void ignoresXffFromUntrustedSource() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16");
        // 10.99.x.x 不在受信段里 → 伪造的 XFF 无效
        assertEquals("10.99.1.1",
            resolver.resolve(req("10.99.1.1", "1.2.3.4", "5.6.7.8")));
    }

    @Test
    @DisplayName("单个 IP 也能作为受信代理")
    void supportsSingleIp() {
        ClientIpResolver resolver = new ClientIpResolver("192.168.1.10");
        assertEquals("203.0.113.7",
            resolver.resolve(req("192.168.1.10", "203.0.113.7", null)));
        assertEquals("192.168.1.11",
            resolver.resolve(req("192.168.1.11", "203.0.113.7", null)));
    }

    @Test
    @DisplayName("多个受信代理用逗号分隔")
    void supportsMultipleProxies() {
        ClientIpResolver resolver = new ClientIpResolver("10.42.0.0/16, 192.168.1.10");
        assertEquals("203.0.113.7",
            resolver.resolve(req("10.42.1.5", "203.0.113.7", null)));
        assertEquals("203.0.113.8",
            resolver.resolve(req("192.168.1.10", "203.0.113.8", null)));
    }
}
