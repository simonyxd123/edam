package com.example.edam.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IP 字面量校验 / 解析 —— <b>不做 DNS 解析</b>。
 *
 * <p>为什么需要它：审计表 {@code operation_log.ip} / {@code play_log.ip}
 * 是 {@code VARCHAR(45)}，而写进去的 IP 可能来自 {@code X-Forwarded-For} ——
 * 那是<b>客户端可以随便填的普通请求头</b>。两个后果：
 * <ul>
 *   <li><b>伪造</b>：审计里的 IP 变成攻击者随手写的字符串 ⇒ 审计失去取证价值，
 *       而且"按 IP 查操作记录"从此不可信；</li>
 *   <li><b>丢失</b>：超过 45 字符 ⇒ MySQL 报 "Data too long for column 'ip'"
 *       ⇒ <b>整条审计记录写不进去</b>（被 AuditService 的 catch 吞掉，只留一条
 *       error 日志）。审计被静默吞掉比记错 IP 严重得多。</li>
 * </ul>
 *
 * <p>所以任何来自请求头的 IP，在落库前都必须先确认"这是一个合法的 IP 字面量"。
 *
 * <p>本类只做<b>字面量</b>解析：{@code 203.0.113.7}、{@code ::1}、
 * {@code ::ffff:192.0.2.1} 认；{@code evil.example.com}、{@code 1.2.3}、
 * {@code 010.0.0.1}、{@code 1.2.3.4:8080} 一律不认 —— 后者交给 DNS 解析
 * 会带来两个副作用（阻塞线程 / 变成 SSRF 的跳板），必须挡住。
 */
public final class IpAddresses {

    /**
     * IP 字符串的最大长度 —— 与 {@code VARCHAR(45)} 对齐。
     *
     * <p>IPv6 最长表示 45 字符（IPv4 映射形式 {@code ::ffff:255.255.255.255}），
     * IPv4 最长 15 字符。超过就是非法输入，直接拒掉。
     */
    public static final int MAX_LENGTH = 45;

    private IpAddresses() {
    }

    /**
     * 是不是合法的 IP 字面量。
     */
    public static boolean isValid(String ip) {
        return toBytes(ip) != null;
    }

    /**
     * 解析成字节数组：IPv4 → 4 字节，IPv6 → 16 字节。
     *
     * @return 非法输入返回 {@code null}
     */
    public static byte[] toBytes(String ip) {
        if (ip == null) {
            return null;
        }
        String s = ip.trim();
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return null;
        }
        return s.indexOf(':') >= 0 ? parseIpv6(s) : parseIpv4(s);
    }

    /**
     * 归一化：去空白后原样返回（保留人读的形态），非法输入返回 {@code null}。
     *
     * <p>不做压缩/展开变换 —— 审计里记 {@code 0:0:0:0:0:0:0:1} 还是
     * {@code ::1} 都无所谓，能查到就行；反倒是别把
     * {@code ::ffff:192.0.2.1} 悄悄变成 {@code 192.0.2.1}，
     * 那样 IPv6 客户端和 IPv4 客户端的记录会混在一起。
     */
    public static String normalize(String ip) {
        if (ip == null) {
            return null;
        }
        String s = ip.trim();
        return isValid(s) ? s : null;
    }

    // ==================================================================
    // IPv4
    // ==================================================================

    /**
     * 严格 IPv4 解析：四段十进制，每段 0–255。
     *
     * <p><b>不接受前导零</b>（{@code 010.0.0.1}）：不同解析器对它是十进制 10
     * 还是八进制 8 各执一词 —— 审计里同一个地址出现两种写法就没法聚合，
     * 而且它还能被用来绕过基于 IP 的匹配规则。
     */
    private static byte[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            if (p.length() > 1 && p.charAt(0) == '0') {
                return null;
            }
            int v = 0;
            for (int c = 0; c < p.length(); c++) {
                char ch = p.charAt(c);
                if (ch < '0' || ch > '9') {
                    return null;
                }
                v = v * 10 + (ch - '0');
            }
            if (v > 255) {
                return null;
            }
            out[i] = (byte) v;
        }
        return out;
    }

    // ==================================================================
    // IPv6
    // ==================================================================

    /**
     * IPv6 解析。
     *
     * <p>先做一遍<b>结构校验</b>（组数、{@code ::} 只能出现一次、每组 1–4 位十六进制、
     * 末组可以是 IPv4），确认它"长得像"一个 IPv6 字面量，再交给
     * {@link InetAddress#getByName(String)} 解析。
     *
     * <p>结构校验不是为了严谨而严谨 —— 它是<b>挡 DNS 查询</b>的：
     * {@code getByName} 对解析不了的字符串会退化成一次域名解析，
     * 那意味着一个伪造的请求头就能让后端去查任意域名（阻塞点 + SSRF 跳板）。
     * 结构校验通过 ⇒ 输入必然是字面量 ⇒ {@code getByName} 只会走字面量分支。
     */
    private static byte[] parseIpv6(String s) {
        if (!looksLikeIpv6(s)) {
            return null;
        }
        try {
            byte[] bytes = InetAddress.getByName(s).getAddress();
            // Java 会把 ::ffff:1.2.3.4 归一成 4 字节的 Inet4Address —— 接受它
            return (bytes.length == 4 || bytes.length == 16) ? bytes : null;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * IPv6 字面量的结构校验（不解析，只看形状）。
     */
    private static boolean looksLikeIpv6(String s) {
        // "::" 最多只能出现一次
        if (s.indexOf("::") != s.lastIndexOf("::")) {
            return false;
        }
        boolean compressed = s.contains("::");
        int groups = 0;
        String[] halves = s.split("::", -1);
        for (String half : halves) {
            if (half.isEmpty()) {
                continue;
            }
            String[] parts = half.split(":", -1);
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (p.isEmpty()) {
                    return false;
                }
                if (p.indexOf('.') >= 0) {
                    // 只允许**最后一段**是 IPv4（IPv4 映射形式）
                    if (i != parts.length - 1 || parseIpv4(p) == null) {
                        return false;
                    }
                    groups += 2;
                    continue;
                }
                if (p.length() > 4) {
                    return false;
                }
                for (int c = 0; c < p.length(); c++) {
                    char ch = p.charAt(c);
                    boolean hex = (ch >= '0' && ch <= '9')
                            || (ch >= 'a' && ch <= 'f')
                            || (ch >= 'A' && ch <= 'F');
                    if (!hex) {
                        return false;
                    }
                }
                groups++;
            }
        }
        // 有 "::" ⇒ 至少要压缩掉 1 组；没有 ⇒ 必须正好 8 组
        return compressed ? groups < 8 : groups == 8;
    }
}
