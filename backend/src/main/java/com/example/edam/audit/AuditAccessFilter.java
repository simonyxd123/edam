package com.example.edam.audit;

import com.example.edam.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 兜底过滤器：记录**没进到控制器就被拒**的请求。
 *
 * <p><b>为什么需要它</b>：{@link AuditAspect} 包的是控制器方法，
 * 但有一类请求根本到不了控制器方法 ——
 * <ul>
 *   <li>JWT 无效 / 过期 / 格式错 ⇒ 过滤器链里就 401 了</li>
 *   <li>URL 级权限不足 ⇒ 403</li>
 * </ul>
 * 这恰恰是"有人在试探系统边界"的直接证据。
 * 方案书 §14.3 STRIDE 把它列为 <b>Repudiation（否认）</b> 与
 * <b>Elevation of Privilege（权限提升）</b> 的缓解手段。
 *
 * <p><b>与切面的分工</b>：
 * <ul>
 *   <li>切面：请求进到了控制器方法（成功/失败/方法级 403 都归它）</li>
 *   <li>过滤器：请求<b>没进</b>控制器方法（过滤器链里的 401/403）</li>
 * </ul>
 * 切面会在请求上打标记（{@link AuditAspect#AUDITED_REQUEST_ATTR}），
 * 过滤器只处理没被标记过的请求 —— 不会重复记账。
 *
 * <p>对应方案书 <b>§7.1</b>（operation_log）与 <b>§14.3 STRIDE</b>。
 *
 * <p><b>注册位置</b>：它必须注册在 Spring Security 的过滤器链<b>内部</b>
 * （{@code addFilterAfter(..., JwtAuthenticationFilter.class)}），
 * 而不是作为普通 Servlet Filter —— 后者会被 Spring Boot 自动注册到
 * Security 链<b>外面</b>，那时 {@code SecurityContextHolder}
 * 已经被 Security 的过滤器清空了，取不到操作者身份。
 * 所以这个类**不是 Bean**，由 {@code SecurityConfig} 直接 new 出来。
 */
@Slf4j
public class AuditAccessFilter extends OncePerRequestFilter {

    /** 这些路径不记审计：健康检查/指标/文档，都是机器访问，量又大又没意义 */
    private static final Set<String> SKIP_PREFIXES = Set.of(
        "/api/v1/health", "/api/v1/actuator", "/actuator",
        "/api/v1/swagger", "/api/v1/v3/api-docs", "/v3/api-docs"
    );

    private final AuditService auditService;
    /** 真实 IP / UA 的**唯一**取法（与切面共用，避免两处实现漂移） */
    private final com.example.edam.util.AuditHelper auditHelper;

    public AuditAccessFilter(AuditService auditService,
                              com.example.edam.util.AuditHelper auditHelper) {
        this.auditService = auditService;
        this.auditHelper = auditHelper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                recordIfRejected(request, response);
            } catch (Exception e) {
                // 审计失败绝不影响业务响应
                log.warn("audit_access_filter_write_failed, uri={}", request.getRequestURI(), e);
            }
        }
    }

    private void recordIfRejected(HttpServletRequest request, HttpServletResponse response) {
        int status = response.getStatus();
        if (status != HttpServletResponse.SC_UNAUTHORIZED
            && status != HttpServletResponse.SC_FORBIDDEN) {
            return;
        }
        // 切面已经记过的（进到了控制器方法）不重复记
        if (Boolean.TRUE.equals(request.getAttribute(AuditAspect.AUDITED_REQUEST_ATTR))) {
            return;
        }
        String uri = request.getRequestURI();
        if (uri != null && SKIP_PREFIXES.stream().anyMatch(uri::startsWith)) {
            return;
        }

        // 未认证时 SecurityContext 是空的 ⇒ user_id 记 null（列已可空）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = null;
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() != null) {
            try {
                userId = Long.valueOf(auth.getPrincipal().toString());
            } catch (NumberFormatException ignored) {
                // principal 不是数字（不是本系统签发的 token）⇒ 就记 null
            }
        }

        auditService.log(userId, AuditOpType.ACCESS_DENIED, "system", null,
            "denied",
            "uri=" + uri + ", method=" + request.getMethod()
                + ", status=" + status + ", reason=" + (status == 401 ? "未认证" : "权限不足"),
            clientIp(request), userAgent(request),
            request.getMethod(), uri, status, null, null);
    }

    private String clientIp(HttpServletRequest request) {
        return auditHelper.extractClientIp(request);
    }

    private String userAgent(HttpServletRequest request) {
        return auditHelper.extractUserAgent(request);
    }
}
