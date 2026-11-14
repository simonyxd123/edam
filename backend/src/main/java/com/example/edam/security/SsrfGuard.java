package com.example.edam.security;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * SSRF 守卫：挡掉"服务端主动发起的请求指向内网"。
 *
 * <p><b>为什么需要它</b>：Webhook 回调地址是**用户填的**，而后端会主动向它
 * POST。没有防护的话，一个员工就能用 Webhook 让后端去扫内网、
 * 打云厂商的元数据服务（{@code 169.254.169.254}），
 * 拿到实例角色凭据。
 *
 * <p><b>检查什么</b>（2026-11-08 收紧）：
 * <ol>
 *   <li>协议只允许 http / https（挡 {@code file:} / {@code gopher:} / {@code jar:}）</li>
 *   <li>主机名不能是回环 / 私网 / 链路本地 / 保留地址
 *       —— IPv4 与 <b>IPv6 都查</b>（原先只查 IPv4，且只查了 {@code ::1}）</li>
 *   <li><b>主机名要解析</b> —— 原先只检查字面量，
 *       {@code http://metadata.internal/} 这种解析到 10.x 的域名能直接通过。
 *       现在把域名解析出来，每个解析结果都要过一遍第 2 条</li>
 * </ol>
 *
 * <p><b>还剩的缺口（已知，不假装没有）</b>：
 * DNS 重绑定 —— 检查发生在解析时刻，连接时刻的第二次解析可能给出不同 IP。
 * 彻底解决要自己实现 DNS 解析 + 直连目标 IP（本仓库不做）。
 * 缓解办法：{@link #assertPublicUrl} 在**投递前**也会再查一次
 * （见 {@code WebhookDeliveryService}），把窗口压到最小。
 *
 * <p>2026-11-14 第六轮补：{@code 100.64.0.0/10}（CGNAT 段，Tailscale
 * 与部分云厂商的内网地址）此前不在私网表里 —— 用它当回调地址可以打到
 * 企业自己的组网内部。
 */
@Slf4j
public final class SsrfGuard {

    private SsrfGuard() {
    }

    /**
     * 校验一个 URL 可以被服务端主动请求。
     *
     * @throws IllegalArgumentException 指向内网 / 协议不支持 / 主机名解析不出来
     */
    public static void assertPublicUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url 不是合法的 URI: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("url 只支持 http / https，当前: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // URI.getHost() 对某些畸形写法返回 null（如带下划线的域名），
            // 但 java.net.URL 能解析出来 —— 那正是绕过点，所以这里要一起判
            throw new IllegalArgumentException("url 缺少主机名（或主机名格式非法）");
        }

        assertPublicHost(host);
    }

    /**
     * 校验一个主机名 / IP 字面量不指向内网。
     *
     * @param host 主机名或 IP 字面量（不含 IPv6 的方括号）
     */
    public static void assertPublicHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase();
        if (h.isEmpty()) {
            throw new IllegalArgumentException("主机名为空");
        }
        // IPv6 字面量：URI.getHost() 会把方括号去掉，这里再兜一层
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }

        // 1) 先按字面量判一次 —— 不依赖 DNS，最快也最可靠
        if (isPrivateLiteral(h)) {
            throw new IllegalArgumentException("url 不允许指向内网 / 回环地址: " + host);
        }

        // 2) 再解析域名，把解析结果也判一遍
        //    （字面量这一步挡不住 "metadata.internal" 这类解析到私网的域名）
        try {
            InetAddress[] addrs = InetAddress.getAllByName(h);
            for (InetAddress addr : addrs) {
                if (isPrivateAddress(addr)) {
                    throw new IllegalArgumentException(
                        "url 解析到内网地址，不允许: " + host + " → " + addr.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            // 解析不出来 = 这个主机根本不存在，Webhook 也永远投不出去，
            // 直接拒（fail-closed），错误信息要能让人看懂
            throw new IllegalArgumentException("url 主机名解析失败: " + host);
        }
    }

    /** 字面量级别的私网判断（不碰 DNS）。 */
    private static boolean isPrivateLiteral(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")
            || host.equals("local") || host.endsWith(".local")
            || host.equals("internal") || host.endsWith(".internal")) {
            return true;
        }
        // 十进制 / 十六进制 IP 写法：2130706433 == 127.0.0.1，0x7f000001 同理
        if (isNumericIpForm(host)) {
            return true;
        }
        if (isIpv4(host)) {
            return isPrivateIpv4(host);
        }
        if (host.contains(":")) {
            // IPv6 字面量
            try {
                return isPrivateAddress(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }

    /** 十进制 / 十六进制 / 八进制写法的 IPv4（{@code 2130706433}、{@code 0x7f000001}）。 */
    private static boolean isNumericIpForm(String host) {
        String h = host;
        try {
            if (h.startsWith("0x") || h.startsWith("0X")) {
                long v = Long.parseLong(h.substring(2), 16);
                return v <= 0xFFFFFFFFL;
            }
            // 纯十进制且没有点 —— 可能是整数形式的 IPv4
            if (!h.contains(".") && h.chars().allMatch(Character::isDigit)) {
                long v = Long.parseLong(h);
                return v <= 0xFFFFFFFFL;
            }
        } catch (NumberFormatException ignored) {
            // 不是数字写法
        }
        return false;
    }

    private static boolean isIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit)) {
                return false;
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    /** IPv4 私网 / 回环 / 链路本地 / 保留段。 */
    private static boolean isPrivateIpv4(String host) {
        String[] p = host.split("\\.");
        int a = Integer.parseInt(p[0]);
        int b = Integer.parseInt(p[1]);
        if (a == 10) return true;                       // 10/8
        if (a == 127) return true;                      // 127/8 回环
        if (a == 0) return true;                        // 0/8
        if (a == 169 && b == 254) return true;          // 169.254/16 链路本地（含云元数据）
        if (a == 172 && b >= 16 && b <= 31) return true;// 172.16/12
        if (a == 100 && b >= 64 && b <= 127) return true; // 100.64/10 CGNAT（Tailscale / 部分云内网）
        if (a == 192 && b == 168) return true;          // 192.168/16
        if (a == 192 && b == 0) return true;            // 192.0/24
        if (a == 198 && (b == 18 || b == 19)) return true; // 198.18/15 基准测试段
        if (a >= 224) return true;                      // 组播 / 保留
        return false;
    }

    /** 解析后的地址是不是内网（IPv4 / IPv6 通吃）。 */
    public static boolean isPrivateAddress(InetAddress addr) {
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(b & 0xFF).append('.');
            }
            sb.setLength(sb.length() - 1);
            return isPrivateIpv4(sb.toString());
        }
        // IPv6
        if (raw.length != 16) {
            return true;    // 认不出来的一律当内网（fail-closed）
        }
        // ::1 回环
        if (isAllZeroExceptLast(raw)) return true;
        // ::ffff:a.b.c.d —— IPv4 映射地址（能绕过只查 IPv4 的漏洞）
        if ((raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
            StringBuilder sb = new StringBuilder();
            for (int i = 12; i < 16; i++) {
                sb.append(raw[i] & 0xFF).append('.');
            }
            sb.setLength(sb.length() - 1);
            return isPrivateIpv4(sb.toString());
        }
        // fc00::/7 唯一本地地址
        if ((raw[0] & 0xFE) == 0xFC) return true;
        // fe80::/10 链路本地
        if ((raw[0] & 0xC0) == 0x80) return true;
        return false;
    }

    private static boolean isAllZeroExceptLast(byte[] raw) {
        for (int i = 0; i < raw.length - 1; i++) {
            if (raw[i] != 0) return false;
        }
        return raw[raw.length - 1] == 1;
    }

    /** 便于测试：把地址数组转成字符串列表。 */
    static String describe(InetAddress[] addrs) {
        return Arrays.toString(addrs);
    }
}
