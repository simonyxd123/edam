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

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
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
                .requestMatchers("/health", "/health/live", "/health/ready").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                // Worker 内部回调：Worker → Backend 更新视频状态
                // 生产应加静态 service token 鉴权（X-Worker-Token）
                .requestMatchers(HttpMethod.PATCH, "/videos/*/status").permitAll()
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
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}