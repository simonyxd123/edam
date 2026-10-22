package com.example.edam.security.sso;

import jakarta.servlet.http.HttpServletRequest;

/**
 * SSO 请求路径工具。
 *
 * <p><b>为什么要专门一个类</b>：Spring Boot 配了
 * {@code server.servlet.context-path=/api/v1}，而
 * {@code request.getRequestURI()} 返回的是**含 context-path 的完整路径**
 * （如 {@code /api/v1/auth/sso/login/xxx}）。原实现直接拿它跟前缀
 * {@code "/auth/sso/login/"} 比较 —— 永远不匹配 → SSO 登录入口、
 * 回调、元数据下载三条路径全是死的。
 *
 * <p>同理 {@code SsoCallbackParser} 用
 * {@code path.substring("/auth/sso/callback/".length())} 切 providerId，
 * 在带 context-path 的情况下会切出 {@code api/v1/auth/sso/callback/xxx}。
 */
public final class SsoRequestPaths {

    public static final String LOGIN_PREFIX = "/auth/sso/login/";
    public static final String CALLBACK_PREFIX = "/auth/sso/callback/";
    public static final String METADATA_PREFIX = "/auth/sso/metadata/";

    private SsoRequestPaths() {
    }

    /**
     * 取去掉 context-path 的请求路径。
     *
     * <p>优先用 {@code getServletPath()}（Spring Boot 下 DispatcherServlet
     * 映射到 "/"，它返回的就是 context-path 之后的路径）；
     * 若为空（容器直接分发到默认 servlet 的情况）再退回手工剥离
     * context-path。
     */
    public static String pathWithinContext(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            String stripped = uri.substring(contextPath.length());
            return stripped.isEmpty() ? "/" : stripped;
        }
        return uri;
    }

    /**
     * 取出路径前缀之后的部分（providerId）。不匹配时返回 null。
     */
    public static String extractAfter(HttpServletRequest request, String prefix) {
        String path = pathWithinContext(request);
        if (path == null || !path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        // providerId 不允许再带斜杠
        if (rest.isEmpty() || rest.contains("/")) {
            return null;
        }
        return rest;
    }
}
