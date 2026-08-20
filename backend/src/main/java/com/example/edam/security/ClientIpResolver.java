package com.example.edam.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 客户端 IP 解析器（v3.2 V-3）
 *
 * 解析顺序（按优先级）：
 * 1. X-Forwarded-For 第一项（标准代理头）
 * 2. X-Real-IP（Nginx 反向代理）
 * 3. request.getRemoteAddr()（直接连接）
 *
 * 安全性：
 * - 注意 X-Forwarded-For 可被客户端伪造，仅信任反向代理
 * - 在 Nginx 中需配置 set_real_ip_from + real_ip_header X-Forwarded-For
 */
@Component
public class ClientIpResolver {

    public String resolve(HttpServletRequest request) {
        // 1. X-Forwarded-For 第一项
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int commaIdx = xff.indexOf(',');
            String first = commaIdx > 0 ? xff.substring(0, commaIdx) : xff;
            return first.trim();
        }

        // 2. X-Real-IP
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }

        // 3. 直连 IP
        return request.getRemoteAddr();
    }
}