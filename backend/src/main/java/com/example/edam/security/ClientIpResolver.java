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
 *
 * <h3>受信名单必须"精确"，否则内网用户会被记错</h3>
 *
 * <p>名单里的每一项都会被当成"我们自己的一跳代理"而<b>跳过</b>。
 * 所以名单一旦画得太宽（比如图省事写 {@code 10.0.0.0/8,172.16.0.0/12}），
 * <b>内网用户的地址也会被跳过</b> ⇒ 外网用户记对了、内网用户全记成网关 IP。
 * 这种"一半对一半错"比全错更难查，所以名单要么精确，要么自动探测。
 *
 * <p>K8s 里 Pod 网段是<b>可以自己读出来的</b>（容器网卡上带着自己的地址和
 * 前缀长度）⇒ 默认自动探测（{@code edam.security.auto-trust-pod-network=true}），
 * 无需运维填任何东西，内网用户与外网用户就都能记准。
 *
 * <p>名单之外还有别的代理时（CDN / 云 LB）在前面补上，例如
 * {@code "13.32.0.0/15,52.222.0.0/16"}。
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /** 拿不到任何地址时的占位值（与库里其它"未知"写法保持一致） */
    public static final String UNKNOWN = "unknown";

    /**
     * 自动探测 Pod 网段时，IPv4 网段最宽放宽到 /16。
     *
     * <p>为什么要放宽：不同 CNI 给容器网卡的前缀长度不一样 ——
     * flannel / Calico / kind 给的是集群 Pod CIDR 的 /16（本来就够宽），
     * 而 AWS VPC CNI 给的是**每个节点**的 /28 ⇒ 只按 /28 算的话，
     * 别的节点上的 ingress Pod 就落在网段外 ⇒ 那一跳不会被跳过 ⇒
     * 审计里记成 ingress Pod 的地址。
     *
     * <p>放宽到 /16 之后覆盖整个集群 Pod 网段（绝大多数集群的 Pod CIDR 都在
     * 一个 /16 里），代价是"同 /16 的内网客户端"会被当成代理 ——
     * 但那种情况下本来就有歧义（见类注释）。
     */
    public static final int AUTO_POD_NETWORK_MAX_PREFIX = 16;

    /** 受信代理（显式配置的）：IP 或 CIDR */
    private final List<CidrBlock> trustedProxies;

    /** 受信代理（自动探测到的 Pod 网段） */
    private final List<CidrBlock> autoPodProxies;

    @org.springframework.beans.factory.annotation.Autowired
    public ClientIpResolver(
            @Value("${edam.security.trusted-proxies:}") String trustedProxiesConfig,
            @Value("${edam.security.auto-trust-pod-network:true}") boolean autoTrustPodNetwork) {
        this(trustedProxiesConfig, autoTrustPodNetwork ? detectPodNetwork() : List.of());
    }

    /**
     * 测试用：直接指定"自动探测到的 Pod 网段"，不依赖运行环境。
     */
    ClientIpResolver(String trustedProxiesConfig, List<CidrBlock> autoPodProxies) {
        this.trustedProxies = parseCidrs(trustedProxiesConfig);
        this.autoPodProxies = autoPodProxies == null ? List.of() : autoPodProxies;

        if (this.trustedProxies.isEmpty() && this.autoPodProxies.isEmpty()) {
            log.warn("edam.security.trusted-proxies 未配置，且未探测到 Pod 网段 —— "
                + "客户端 IP 一律取自 socket，反向代理场景下所有请求会被记成代理 IP"
                + "（限流也按代理聚合）。"
                + "开发环境请配 127.0.0.1/32,::1/128；"
                + "K8s 请确认 Pod 网段能被自动探测到，否则显式配 ingress/网关的网段。");
        } else {
            log.info("trusted_proxies_configured, configured={}, auto_pod_network={}",
                this.trustedProxies.size(), describe(this.autoPodProxies));
        }
    }

    /** 测试用：不做自动探测 */
    public ClientIpResolver(String trustedProxiesConfig) {
        this(trustedProxiesConfig, List.of());
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
            log.debug("client_ip_resolved, peer={}, result={}, reason=对端不是受信代理",
                peer, peer);
            return peer;
        }
        String viaXff = pickFromForwardedFor(xForwardedFor);
        if (viaXff != null) {
            log.debug("client_ip_resolved, peer={}, xff={}, result={}, reason=XFF",
                peer, xForwardedFor, viaXff);
            return viaXff;
        }
        String viaRealIp = IpAddresses.normalize(xRealIp);
        if (viaRealIp != null && !isTrustedProxy(viaRealIp)) {
            log.debug("client_ip_resolved, peer={}, x_real_ip={}, result={}, reason=X-Real-IP",
                peer, viaRealIp, viaRealIp);
            return viaRealIp;
        }
        log.debug("client_ip_resolved, peer={}, xff={}, x_real_ip={}, result={}, reason=回退到对端地址",
            peer, xForwardedFor, xRealIp, peer);
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
     * 地址是否属于受信代理（显式配置的名单 + 自动探测到的 Pod 网段）。
     */
    public boolean isTrustedProxy(String addr) {
        if (addr == null || addr.isBlank()) {
            return false;
        }
        return matches(trustedProxies, addr) || matches(autoPodProxies, addr);
    }

    private static boolean matches(List<CidrBlock> blocks, String addr) {
        for (CidrBlock block : blocks) {
            if (block.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // Pod 网段自动探测
    // ==================================================================

    /**
     * 探测自己所在容器的网络 —— 也就是 K8s 的 Pod 网段。
     *
     * <p><b>为什么要探测，而不是让运维填</b>：受信名单必须是**精确**的。
     * 如果图省事填"整个内网"（10.0.0.0/8、172.16.0.0/12），那么
     * <b>内网用户的地址也会被当成"代理跳"跳过</b> ⇒ 审计里内网用户全部记成
     * 网关 IP，而外网用户是对的 —— 这种"一半对一半错"比全错更难查。
     *
     * <p>Pod 网段是可以自己读出来的：容器网卡上带着自己的地址和前缀长度
     * （{@code InterfaceAddress.getNetworkPrefixLength()}），
     * 由它推出的网段就是 CNI 分配的 Pod CIDR。
     *
     * <p>只在 K8s 里做（靠 {@code KUBERNETES_SERVICE_HOST} 判断）——
     * 裸机 / docker-compose 环境下容器网卡的前缀是**宿主机的局域网**，
     * 那时候把局域网当"代理网段"会让内网客户端被跳过，正好搞反了。
     */
    static List<CidrBlock> detectPodNetwork() {
        if (System.getenv("KUBERNETES_SERVICE_HOST") == null) {
            return List.of();
        }
        List<CidrBlock> blocks = new ArrayList<>();
        try {
            for (java.net.NetworkInterface nic
                    : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    CidrBlock block = podNetworkOf(ia.getAddress(), ia.getNetworkPrefixLength());
                    if (block != null) {
                        blocks.add(block);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("pod_network_detect_failed, error={}", e.getMessage());
            return List.of();
        }
        if (blocks.isEmpty()) {
            log.warn("pod_network_detect_empty —— 未能在容器网卡上找到 Pod 地址，"
                + "请显式配置 edam.security.trusted-proxies（ingress / 网关的网段）。");
        }
        return blocks;
    }

    /**
     * 由容器自己的地址 + 前缀长度推出 Pod 网段。
     *
     * @return 推不出来（比如地址非法）时返回 {@code null}
     */
    static CidrBlock podNetworkOf(java.net.InetAddress address, int prefixLength) {
        if (address == null || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
            return null;
        }
        byte[] bytes = address.getAddress();
        int bits = bytes.length * 8;
        int prefix = prefixLength;
        if (prefix <= 0 || prefix > bits) {
            // 前缀拿不到（有些环境返回 0 / -1）⇒ 按地址族取个保守值
            prefix = bits;
        }
        if (bytes.length == 4) {
            // IPv4：放宽到最多 /16（见 AUTO_POD_NETWORK_MAX_PREFIX 的注释）
            prefix = Math.min(prefix, AUTO_POD_NETWORK_MAX_PREFIX);
        }
        return CidrBlock.fromBytes(bytes, prefix);
    }

    private static String describe(List<CidrBlock> blocks) {
        if (blocks.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (CidrBlock b : blocks) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(b);
        }
        return sb.toString();
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

        /**
         * 由字节数组直接建网段（自动探测 Pod 网段时用 —— 地址来自网卡，不是字符串）。
         */
        static CidrBlock fromBytes(byte[] bytes, int prefixBits) {
            byte[] network = bytes.clone();
            applyMask(network, prefixBits);
            return new CidrBlock(network, prefixBits);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (network.length == 4) {
                for (int i = 0; i < 4; i++) {
                    if (i > 0) {
                        sb.append('.');
                    }
                    sb.append(network[i] & 0xFF);
                }
            } else {
                for (int i = 0; i < 16; i += 2) {
                    if (i > 0) {
                        sb.append(':');
                    }
                    sb.append(String.format("%x", ((network[i] & 0xFF) << 8) | (network[i + 1] & 0xFF)));
                }
            }
            return sb.append('/').append(prefixBits).toString();
        }
    }
}
