package com.example.edam.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * JWT 鉴权过滤器
 * - 解析 Authorization Header
 * - 验证签名（HS256 或 GS256，由 {@link JwtTokenProvider} 按 header 派发）
 * - 设置 Spring Security Context
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 验签走 provider —— 它会按 token header 里的 alg 派发
     * （HS256 / GS256 国密）。
     *
     * <p>此前过滤器**自己**解析了一遍（固定用 HMAC-SHA256），
     * 于是即便签发端切到国密，验签端也不认 —— 两边必须一起改才行。
     */
    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtTokenProvider.parseAndValidate(token);
                // principal 用 subject（= String.valueOf(userId)），便于后续
                // SecurityContextHolder.getContext().getAuthentication().getName()
                // 直接拿到 userId 字符串，配合 AuthService 的查询。
                // user_id_hash 保留在 claims 里用于审计，不暴露明文 PII。
                String userId = claims.getSubject();
                String userIdHash = claims.get("user_id_hash", String.class);
                String sessionId = claims.get("session_id", String.class);

                @SuppressWarnings("unchecked")
                List<String> roles = claims.get("roles", List.class);
                List<String> permissions = claims.get("permissions", List.class);

                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                if (roles != null) {
                    roles.forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
                }
                if (permissions != null) {
                    permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                }

                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                // 把 hash 挂到 details 里供审计 / 日志使用，不污染 principal
                authentication.setDetails(Map.of(
                    "remoteAddress", request.getRemoteAddr(),
                    "sessionId", sessionId == null ? "" : sessionId,
                    "userIdHash", userIdHash == null ? "" : userIdHash
                ));
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (Exception e) {
                log.debug("JWT 验证失败: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}