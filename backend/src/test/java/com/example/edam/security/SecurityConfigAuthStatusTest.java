package com.example.edam.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权失败时的状态码契约（走真实的 {@link SecurityConfig} 过滤器链）。
 *
 * <p><b>为什么要有这个测试</b>：Spring Security 在没有显式配置
 * {@code authenticationEntryPoint} 时，默认用 {@code Http403ForbiddenEntryPoint}
 * —— <b>未认证也返回 403</b>，且 body 为空。这类偏差不会被单元测试发现
 * （单测测的是我们自己的 handler），只有真正走一遍过滤器链才看得出来；
 * 而它一旦跑偏，前端的 refresh_token 静默续期就整个失效
 * （前端只在 <b>401</b> 时才去续期，403 被当成"权限不足"直接弹窗 ——
 * 而 access_token 只有 10 分钟 TTL，等于每 10 分钟把用户踢回登录页）。
 *
 * <p>断言的是 <b>契约</b>（{@code doc/openapi.yaml} 里所有受保护端点声明的
 * 都是 401 Unauthorized），不是实现细节。
 *
 * <p>用 {@code classes = ...} 显式列出配置类：<b>不做组件扫描</b>，
 * 于是既不会拖进 MyBatis / MySQL，也能在本地跑。
 */
@SpringBootTest(classes = {
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        SecurityConfigAuthStatusTest.TestBeans.class,
        SecurityConfigAuthStatusTest.ProbeController.class
})
@EnableAutoConfiguration(exclude = {
        org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration.class,
        com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class
})
@AutoConfigureMockMvc
@DisplayName("鉴权失败的状态码契约")
class SecurityConfigAuthStatusTest {

    @TestConfiguration
    static class TestBeans {
        /** 只需要一个"什么都不做"的 JWT 过滤器：本测试关注的是链路的响应码。 */
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request,
                                                HttpServletResponse response,
                                                FilterChain chain)
                        throws ServletException, IOException {
                    chain.doFilter(request, response);
                }
            };
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe-protected")
        public String probe() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("未认证访问受保护端点返回 401（不是 403），且 body 是 Problem Details")
    void unauthenticatedGets401WithProblemDetails() throws Exception {
        mockMvc.perform(get("/probe-protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("AUTH_401"))
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    @DisplayName("公开端点不要求 token（未认证时不该是 401/403）")
    void publicEndpointsNeedNoToken() throws Exception {
        // 用 permitAll 覆盖的路径断言"不是 401/403"：本测试没有挂真实的
        // HealthController（它需要 DB/Redis/RabbitMQ 的 bean），所以只能断言
        // 鉴权层放行、请求走到 404（没有 handler）而不是被鉴权拦下。
        mockMvc.perform(get("/auth/login")).andExpect(status().isNotFound());
        mockMvc.perform(get("/health/live")).andExpect(status().isNotFound());
    }
}
