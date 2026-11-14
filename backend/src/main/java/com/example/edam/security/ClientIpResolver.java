package com.example.edam.security;

import com.example.edam.util.IpAddresses;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 IP 解析器 —— <b>全系统唯一的取 IP 口径</b>。
 *
 * <p>审计日志（{@code operation_log.ip} / {@code play_log.ip}）、登录限流、
 * WebAuthn 限流、播放上报全都走这里。口径必须只有一份 ——
 * 否则就会出现"审计里记的是代理 IP、限流按真实 IP 算"这种对不上的情况。
 *
 * <h3>解析顺序</h3>
 * <ol>
 *   <li><b>对端（socket）地址</b> —— {@code request.getRemoteAddr()}。
 *       它是<b>唯一无法伪造</b>的来源：TCP 连接的对端地址。</li>
 *   <li>对端<b>不在</b>受信代理名单里 ⇒ 直接返回对端地址，
 *       <b>完全无视</b> {@code X-Forwarded-For} / {@code X-Real-IP}。
 *       这两个头是普通请求头，客户端可以随便填。</li>
 *   <li>对端在受信名单里 ⇒ 才采信转发头，且按下面的顺序取。</li>
 * </ol>
 *
 * <h3>转发头怎么取</h3>
 * <ul>
 *   <li><b>X-Forwarded-For</b>：<b>从右往左</b>扫，跳过属于受信代理的地址，
 *       取第一个"不是我们自己的代理"的合法 IP。
 *       <br>为什么从右往左：代理是把对端地址<b>追加</b>到 XFF 末尾的
 *       （nginx 的 {@code $proxy_add_x_forwarded_for}），
 *       客户端只能<b>前置</b>伪造值 —— 所以最右边那一项一定是真的，
 *       越往左越可能是客户端塞进来的。
 *       <br>典型形态：客户端伪造 {@code XFF: 1.2.3.4}，经 nginx 后变成
 *       {@code 1.2.3.4, <真实客户端IP>} ⇒ 扫到最右边的真实 IP 就停 ⇒ 伪造值被忽略。</li>
 *   <li><b>X-Real-IP</b>：只在 XFF 里拿不到可用地址时兜底。
 *       nginx 用 {@code proxy_set_header X-Real-IP $remote_addr} <b>覆盖写</b>，
 *       客户端伪造不了；但如果它的值本身是受信代理地址（多级代理场景），
 *       说明那还是一跳代理，同样跳过。</li>
 *   <li>都没有 ⇒ 退回对端地址。</li>
 * </ul>
 *
 * <h3>为什么每次都要校验合法性</h3>
 * 见 {@link IpAddresses}：IP 会写进 {@code VARCHAR(45)} 的列，
 * 而它是客户端可控的 —— 不校验就既能伪造审计记录，又能用超长值把审计写入打挂。
 *
 * <h3>配置</h3>
 * <p>{@code edam.security.trusted-proxies}，支持 IP 与 CIDR，逗号分隔。
 * <b>留空 = 不信任任何代理</b>（fail-closed，一律用 socket 真实 IP）。
 *
 * <p>开发环境（vite dev server 代理，peer = 127.0.0.1）：
 * <pre>
 * edam:
 *   security:
 *     trusted-proxies: "127.0.0.1/32,::1/128"
 * </pre>
 * K8s（ingress → nginx 网关 → 后端）必须配网关 / ingress 的 Pod 网段，
 * 否则审计里记的是网关 Pod 的 IP。
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /** 拿不到任何地址时的占位值（与库里其它"未知"写法保持一致） */
    public static final String UNKNOWN = "unknown";

    /** 受信代理：IP 或 CIDR */
    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(
            @Value("${edam.security.trusted-proxies:}") String trustedProxiesConfig) {
        this.trustedProxies = parseCidrs(trustedProxiesConfig);
        if (this.trustedProxies.isEmpty()) {
            log.warn("edam.security.trusted-proxies 未配置 —— 客户端 IP 一律取自 socket，"
                + "反向代理场景下所有请求会被记为代理 IP（限流也按代理聚合）。"
                + "开发环境请配 127.0.0.1/32,::1/128，K8s 请配 ingress/网关的 Pod 网段。");
        } else {
            log.info("trusted_proxies_configured, count={}", this.trustedProxies.size());
        }
    }

    /**
     * 从请求里解析真实客户端 IP。
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        return resolve(request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"));
    }

    /**
     * 解析入口（拆出来是为了能直接单测，不用造 HttpServletRequest）。
     *
     * @param peerAddr        对端地址（{@code getRemoteAddr()}）
     * @param xForwardedFor   {@code X-Forwarded-For} 头
     * @param xRealIp         {@code X-Real-IP} 头
     * @return 真实客户端 IP；实在拿不到时返回 {@link #UNKNOWN}
     */
    public String resolve(String peerAddr, String xForwardedFor, String xRealIp) {
        String peer = IpAddresses.normalize(peerAddr);
        if (peer == null) {
            // 对端地址都不合法 —— 说明不是正常进来的请求，记 unknown
            return UNKNOWN;
        }
        // 对端不是受信代理 ⇒ 转发头一律不采信（它们是客户端可控的）
        if (!isTrustedProxy(peer)) {
            return peer;
        }
        String viaXff = pickFromForwardedFor(xForwardedFor);
        if (viaXff != null) {
            return viaXff;
        }
        String viaRealIp = IpAddresses.normalize(xRealIp);
        if (viaRealIp != null && !isTrustedProxy(viaRealIp)) {
            return viaRealIp;
        }
        return peer;
    }

    /**
     * 从 XFF 里挑出真实客户端地址。
     *
     * <p>从右往左扫（见类注释）：越靠右越是可信的代理自己写下的。
     * 属于受信代理网段的项直接跳过（那是中间跳，不是客户端）。
     */
    private String pickFromForwardedFor(String xForwardedFor) {
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return null;
        }
        String[] parts = xForwardedFor.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String raw = parts[i].trim();
            if (raw.isEmpty()) {
                continue;
            }
            String ip = IpAddresses.normalize(raw);
            if (ip == null) {
                // 非法项（伪造值 / 带端口 / 超长）—— 跳过，别让它进库
                continue;
            }
            if (isTrustedProxy(ip)) {
                // 这一跳是我们自己的代理 ⇒ 继续往左找真正的客户端
                continue;
            }
            return ip;
        }
        return null;
    }

    /**
     * 地址是否属于受信代理（IP 或 CIDR）。
     */
    public boolean isTrustedProxy(String addr) {
        if (addr == null || addr.isBlank()) {
            return false;
        }
        for (CidrBlock block : trustedProxies) {
            if (block.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    private static List<CidrBlock> parseCidrs(String config) {
        List<CidrBlock> result = new ArrayList<>();
        if (config == null || config.isBlank()) {
            return result;
        }
        for (String raw : config.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                result.add(CidrBlock.parse(s));
            } catch (IllegalArgumentException e) {
                log.warn("trusted_proxy_parse_failed, value={}, error={}", s, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 最小可用的 CIDR 匹配（IPv4 / IPv6 通用）。
     *
     * <p>按字节比较：把地址和网段都归一到同一族（IPv4 = 4 字节，IPv6 = 16 字节），
     * 逐字节比前 {@code prefixBits} 位。
     */
    static final class CidrBlock {
        private final byte[] network;
        private final int prefixBits;

        private CidrBlock(byte[] network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        static CidrBlock parse(String s) {
            String addrPart = s;
            Integer prefix = null;
            int slash = s.indexOf('/');
            if (slash >= 0) {
                addrPart = s.substring(0, slash);
                try {
                    prefix = Integer.parseInt(s.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("非法 CIDR 前缀: " + s);
                }
            }
            byte[] addr = IpAddresses.toBytes(addrPart);
            if (addr == null) {
                throw new IllegalArgumentException("不是合法的 IP 地址: " + addrPart);
            }
            int maxBits = addr.length * 8;
            int bits = prefix == null ? maxBits : prefix;
            if (bits < 0 || bits > maxBits) {
                throw new IllegalArgumentException("非法 CIDR 前缀: " + s);
            }
            byte[] network = addr.clone();
            applyMask(network, bits);
            return new CidrBlock(network, bits);
        }

        boolean contains(String ip) {
            byte[] addr = IpAddresses.toBytes(ip);
            if (addr == null || addr.length != network.length) {
                return false;
            }
            for (int i = 0; i < network.length; i++) {
                int keep = bitsKeptInByte(i);
                if (keep == 0) {
                    break;
                }
                int mask = (0xFF << (8 - keep)) & 0xFF;
                if ((addr[i] & mask) != (network[i] & mask)) {
                    return false;
                }
            }
            return true;
        }

        /** 第 {@code index} 个字节里有几个比特属于前缀 */
        private int bitsKeptInByte(int index) {
            int bitsBefore = index * 8;
            return Math.max(0, Math.min(8, prefixBits - bitsBefore));
        }

        /** 把地址按前缀长度清零（保留网络部分） */
        private static void applyMask(byte[] bytes, int prefixBits) {
            for (int i = 0; i < bytes.length; i++) {
                int bitsBefore = i * 8;
                int keep = Math.max(0, Math.min(8, prefixBits - bitsBefore));
                int mask = keep == 0 ? 0 : ((0xFF << (8 - keep)) & 0xFF);
                bytes[i] = (byte) (bytes[i] & mask);
            }
        }
    }
}
