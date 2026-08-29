package com.example.edam.util;

import com.example.edam.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 审计日志辅助（v3.2 V-1 RBAC）
 *
 * 解决反向代理 / FRP / nginx 场景下 request.getRemoteAddr() 返回 127.0.0.1 的问题：
 * 优先读 X-Forwarded-For 第一个 IP（用户真实地址），再退化到 X-Real-IP / RemoteAddr。
 *
 * Controller 里统一调 logAudit(...) 即可。
 */
@Component
public class AuditHelper {

    @Autowired
    private AuditService auditService;

    /**
     * 提取真实客户端 IP
     * 顺序：X-Forwarded-For 第一个 → X-Real-IP → RemoteAddr
     */
    public String extractClientIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For 可能是 "client, proxy1, proxy2" → 取第一个
            int comma = xff.indexOf(',');
            String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty() && !"unknown".equalsIgnoreCase(first)) {
                return first;
            }
        }
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 提取 User-Agent（脱敏到 200 字符）
     */
    public String extractUserAgent(HttpServletRequest request) {
        if (request == null) return "unknown";
        String ua = request.getHeader("User-Agent");
        if (ua == null) return "unknown";
        if (ua.length() > 200) ua = ua.substring(0, 200);
        return ua;
    }

    /**
     * 一站式调 auditService.log（自动解析 IP / UA）
     * @param userId         操作者 user_id（匿名场景可传 null）
     * @param operationType  operation_type（如 login / logout / preview / create / update / delete）
     * @param resourceType  resource_type（如 user / role / video / document / auth）
     * @param resourceId    resource_id（具体资源的 id）
     * @param result         "success" / "failure" / "denied"
     * @param detail         详细信息（人读字符串）
     * @param request       HttpServletRequest（自动解析 IP / UA）
     */
    public void logAudit(Long userId, String operationType, String resourceType,
                         Long resourceId, String result, String detail,
                         HttpServletRequest request) {
        String ip = extractClientIp(request);
        String ua = extractUserAgent(request);
        auditService.log(userId, operationType, resourceType, resourceId,
            result, detail, ip, ua);
    }
}
