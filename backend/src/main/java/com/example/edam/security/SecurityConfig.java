package com.example.edam.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置
 * - 禁用 CSRF（前后端分离）
 * - 禁用 Session（使用 JWT）
 * - 公开端点：/auth/**、/health/**
 * - 其他所有请求需 JWT
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)   // v3.2 V-1 RBAC: 启用 @PreAuthorize
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    /**
     * 审计 Service 用 {@link ObjectProvider} 而不是直接注入。
     *
     * <p>原因：{@code SecurityConfigAuthStatusTest} 是一个**切片测试** ——
     * 它刻意不加载 DataSource / MyBatis（{@code @WebMvcTest} 风格），
     * 于是没有 {@code AuditService} bean。直接注入会让整个上下文起不来，
     * 而这个测试要验的其实是"公开端点不带 token 也能过"。
     *
     * <p>{@code ObjectProvider.getIfAvailable()} 在 bean 存在时正常拿到，
     * 不存在时返回 null ⇒ 审计兜底过滤器不注册 ——
     * 生产环境里 AuditService 一定在（它是 @Service），所以审计不会被关掉。
     */
    private final org.springframework.beans.factory.ObjectProvider<com.example.edam.service.AuditService> auditServiceProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.example.edam.util.AuditHelper> auditHelperProvider;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          org.springframework.beans.factory.ObjectProvider<com.example.edam.service.AuditService> auditServiceProvider,
                          org.springframework.beans.factory.ObjectProvider<com.example.edam.util.AuditHelper> auditHelperProvider) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.auditServiceProvider = auditServiceProvider;
        this.auditHelperProvider = auditHelperProvider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/login", "/auth/refresh").permitAll()
                // SSO：登录跳转 / 回调 / 元数据 / 一次性登录码交换
                // 都要匿名可达（回调发生在用户还没拿到 token 的时候）
                .requestMatchers("/auth/sso/**").permitAll()
                // WebAuthn 无密码登录：登录两步都要匿名可达
                // （用户此刻还没有 token）。注册与凭据管理仍需已登录身份。
                // 端点内部有登录限流（IP 5/min + 工号 3/min）。
                .requestMatchers(HttpMethod.POST,
                    "/auth/webauthn/login/begin", "/auth/webauthn/login/complete").permitAll()
                .requestMatchers("/health", "/health/live", "/health/ready").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                // Worker 内部回调：Worker → Backend 更新视频状态
                // 生产应加静态 service token 鉴权（X-Worker-Token）
                .requestMatchers(HttpMethod.PATCH, "/videos/*/status").permitAll()
                // Worker 内部回调：Worker → Backend 更新文档状态
                // 鉴权同样在 Controller 方法内（X-Worker-Token，fail-closed）
                .requestMatchers(HttpMethod.PATCH, "/documents/*/status").permitAll()
                // HLS 播放：Hls.js 不能带 JWT，token 走 query string
                // 方法内部 validateToken() 校验（短期 JWT，10 分钟过期）
                // 生产应换 presigned MinIO URL
                .requestMatchers(HttpMethod.GET, "/playback/*/playlist.m3u8").permitAll()
                .requestMatchers(HttpMethod.GET, "/playback/*/segment/*").permitAll()
                // AES-128 密钥同理：Hls.js 拉 key 时也带不了自定义头，
                // 走 query token + 方法内校验
                .requestMatchers(HttpMethod.GET, "/playback/*/key").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // 未认证 → 401、已认证但权限不足 → 403，都带 Problem Details body。
            // 不配这一段的话，Spring Security 默认对未认证请求返回 403 空 body，
            // 前端 client.ts 的 "401 → 用 refresh_token 换新 token" 流程就永远不会触发。
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler));

        // 审计兜底过滤器：记录"没进控制器就被 401/403 挡下"的请求。
        // 必须放在 Security 链**内部**、JWT 过滤器**之后** ——
        // 这样 finally 里 SecurityContextHolder 还留着操作者身份
        // （放到链外会被 Security 的过滤器清空）。
        // 放在链外还有个更麻烦的后果：Spring Boot 会把它当成普通
        // Servlet Filter 再自动注册一份 ⇒ 同一个请求记两条。
        //
        // 没有 AuditService bean 时（切片测试）不注册 ——
        // addFilterAfter 不接受 null。
        com.example.edam.audit.AuditAccessFilter auditFilter = auditAccessFilter();
        if (auditFilter != null) {
            http.addFilterAfter(auditFilter, JwtAuthenticationFilter.class);
        }

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 审计兜底过滤器（bean 不存在时返回 null ⇒ 不注册）。
     *
     * @see #auditServiceProvider
     */
    private com.example.edam.audit.AuditAccessFilter auditAccessFilter() {
        com.example.edam.service.AuditService auditService = auditServiceProvider.getIfAvailable();
        com.example.edam.util.AuditHelper auditHelper = auditHelperProvider.getIfAvailable();
        if (auditService == null || auditHelper == null) {
            return null;
        }
        return new com.example.edam.audit.AuditAccessFilter(auditService, auditHelper);
    }
}