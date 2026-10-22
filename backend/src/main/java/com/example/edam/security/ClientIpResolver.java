package com.example.edam.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 IP 解析器（v3.2 V-3）
 *
 * 解析顺序：
 * 1. 若来源是**受信代理** → 取 X-Forwarded-For 第一项（或 X-Real-IP）
 * 2. 否则 → 用 request.getRemoteAddr()
 *
 * <p><b>安全说明</b>：X-Forwarded-For / X-Real-IP 是普通请求头，
 * 客户端可以随便填。原实现无条件采信，于是攻击者每次请求换一个
 * XFF 值就能绕过"单 IP 限流"——而 IP 限流是防暴力破解的最后一道
 * 防线（除工号维度和账号锁定外）。
 *
 * <p>现在只信任**配置的白名单代理**传来的头：
 * {@code edam.security.trusted-proxies}，支持 IP 与 CIDR，逗号分隔。
 * 留空 = 不信任任何代理（fail-closed，一律用 socket 真实 IP）。
 *
 * <p>K8s 里必须配（否则所有请求看起来都来自 ingress pod）：
 * <pre>
 * edam:
 *   security:
 *     trusted-proxies: "10.42.0.0/16,10.43.0.0/16"
 * </pre>
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /** 受信代理：IP 或 CIDR */
    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(
            @Value("${edam.security.trusted-proxies:}") String trustedProxiesConfig) {
        this.trustedProxies = parseCidrs(trustedProxiesConfig);
        if (this.trustedProxies.isEmpty()) {
            log.warn("edam.security.trusted-proxies 未配置 —— 客户端 IP 一律取自 socket，"
                + "反向代理场景下所有请求会被记为代理 IP（限流按代理聚合）。"
                + "K8s 请配置 ingress 的 CIDR。");
        } else {
            log.info("trusted_proxies_configured, count={}", this.trustedProxies.size());
        }
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // 只有来自受信代理的请求才采信转发头
        if (isTrustedProxy(remoteAddr)) {
            // 1. X-Forwarded-For 第一项
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int commaIdx = xff.indexOf(',');
                String first = commaIdx > 0 ? xff.substring(0, commaIdx) : xff;
                first = first.trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
            // 2. X-Real-IP
            String xri = request.getHeader("X-Real-IP");
            if (xri != null && !xri.isBlank()) {
                return xri.trim();
            }
        }
        // 3. 直连 IP
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        for (CidrBlock block : trustedProxies) {
            if (block.contains(remoteAddr)) {
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
     * 最小可用的 IPv4 CIDR 匹配。
     *
     * <p>只支持 IPv4（K8s 集群内网场景足够）；IPv6 字面量会被当作
     * 非法地址处理，匹配失败即视为不可信。
     */
    static final class CidrBlock {
        private final long network;
        private final long mask;

        private CidrBlock(long network, long mask) {
            this.network = network;
            this.mask = mask;
        }

        static CidrBlock parse(String s) {
            if (s.contains("/")) {
                String[] parts = s.split("/");
                long addr = toLong(parts[0]);
                int prefix = Integer.parseInt(parts[1].trim());
                if (prefix < 0 || prefix > 32) {
                    throw new IllegalArgumentException("非法 CIDR 前缀: " + prefix);
                }
                long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                return new CidrBlock(addr & mask, mask);
            }
            long addr = toLong(s);
            return new CidrBlock(addr, 0xFFFFFFFFL);
        }

        boolean contains(String ip) {
            try {
                long addr = toLong(ip);
                return (addr & mask) == network;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private static long toLong(String ip) {
            String[] parts = ip.trim().split("\\.");
            if (parts.length != 4) {
                throw new IllegalArgumentException("不是合法的 IPv4 地址: " + ip);
            }
            long v = 0;
            for (String p : parts) {
                int octet;
                try {
                    octet = Integer.parseInt(p.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("非法 IPv4 段: " + p);
                }
                if (octet < 0 || octet > 255) {
                    throw new IllegalArgumentException("非法 IPv4 段: " + p);
                }
                v = (v << 8) | octet;
            }
            return v;
        }
    }
}
