package com.example.edam.util;

import com.example.edam.security.ClientIpResolver;
import com.example.edam.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 审计日志辅助（v3.2 V-1 RBAC）
 *
 * 解决反向代理 / FRP / nginx 场景下 request.getRemoteAddr() 返回 127.0.0.1 的问题。
 *
 * <p><b>取 IP 的口径只有一份</b>：{@link ClientIpResolver}。
 * 此前这里自己抄了一份"取 XFF 第一个"的实现，而限流那边用的是带白名单的
 * {@code ClientIpResolver} —— 两套口径必然对不上：
 * <ul>
 *   <li>审计侧无条件采信 XFF ⇒ 客户端可以伪造审计里的 IP；</li>
 *   <li>且不校验合法性 ⇒ 一个超长字符串就能让整条审计记录写不进
 *       {@code VARCHAR(45)} 的列（审计被静默吞掉）。</li>
 * </ul>
 * 现在两边统一走白名单 + 合法性校验的解析器。
 *
 * Controller 里统一调 logAudit(...) 即可。
 */
@Component
public class AuditHelper {

    private final AuditService auditService;
    /** 真实 IP 的**唯一**取法 */
    private final ClientIpResolver clientIpResolver;

    public AuditHelper(AuditService auditService, ClientIpResolver clientIpResolver) {
        this.auditService = auditService;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * 提取真实客户端 IP。
     *
     * @see ClientIpResolver#resolve(HttpServletRequest)
     */
    public String extractClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
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
