package com.example.edam.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 当前登录用户身份的统一取值入口。
 *
 * <p><b>为什么需要它</b>：本项目此前的所有 controller 都用
 * {@code @RequestHeader("X-User-Id")} 取当前用户。那个头是
 * <b>客户端可任意伪造</b> 的 —— 攻击者把它改成 {@code 1} 就能以
 * admin 身份上传 / 删除 / 重置他人资源，并伪造审计日志。
 *
 * <p>真正的身份已经在 {@link JwtAuthenticationFilter} 里解析进
 * Spring Security 的 SecurityContext（principal = userId 字符串），
 * 这里只是把它取出来。
 *
 * <p>用法：
 * <pre>{@code
 * Long userId = CurrentUser.requireUserId();   // 未登录 → 401
 * Long userId = CurrentUser.optionalUserId();  // 未登录 → null
 * }</pre>
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * 取当前登录用户 ID；未登录或 principal 不可解析时抛 401。
     */
    public static Long requireUserId() {
        Long id = optionalUserId();
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或会话已失效");
        }
        return id;
    }

    /**
     * 取当前登录用户 ID；未登录时返回 null（用于"可选身份"的只读端点）。
     */
    public static Long optionalUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal == null) {
            return null;
        }
        try {
            return Long.valueOf(principal.toString());
        } catch (NumberFormatException e) {
            // principal 不是数字 → 不是本系统签发的 token
            return null;
        }
    }

    /**
     * 当前用户是否拥有指定权限码（权限码直接进 JWT claims，权威来源）。
     *
     * <p>用于 service 层的"归属校验"：非 owner 删除时，需要额外判断
     * 对方是不是管理员。
     */
    public static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream()
            .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    /**
     * 当前用户是否是管理员（admin 角色）。
     *
     * <p>角色码与权限码都写进了 JWT claims（见 JwtTokenProvider），
     * 所以这个判断不依赖数据库查询。
     */
    public static boolean isAdmin() {
        return hasAuthority("ROLE_admin")
            || hasAuthority("ROLE_ADMIN")
            || hasAuthority("admin");
    }
}
