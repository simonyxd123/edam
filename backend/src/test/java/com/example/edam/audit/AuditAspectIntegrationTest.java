package com.example.edam.audit;

import com.example.edam.service.AuditService;
import com.example.edam.util.AuditHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>切面在真实 Spring 代理上的行为</b>（真的发一个 HTTP 请求，走完整的
 * 过滤器链 + Spring MVC + AOP 代理链）。
 *
 * <p><b>这个用例是为了堵一个真实踩过的坑</b>：
 * 原来的 advice 写成 {@code around(ProceedingJoinPoint pjp, AuditOp auditOp)}，
 * 切点写成 {@code @annotation(auditOp)} —— 也就是让 Spring 把注解当形参绑进来。
 * 这种写法<b>依赖 {@code ExposeInvocationInterceptor}</b>：
 * 只有它跑过之后，Spring 才拿得到 {@code JoinPointMatch}，才绑得上注解。
 * 而本切面为了包住 Spring Security 的方法级授权拦截（{@code @PreAuthorize}），
 * 用的正是 {@code HIGHEST_PRECEDENCE} ⇒ 注解永远绑不上 ⇒
 * {@code IllegalStateException: Required to bind 2 arguments, but only bound 1}
 * ⇒ <b>每一个被审计的接口都 500</b>（线上就是这么炸的）。
 *
 * <p><b>为什么必须是"Web 上下文 + 过滤器链"</b>：
 * 纯 POJO 的 {@code @SpringJUnitConfig} 上下文里这个坑<b>不复现</b> ——
 * 拦截器链的组成不同，{@code ExposeInvocationInterceptor} 恰好排在切面前面。
 * 只有把真实的 Spring Security 过滤器链和 {@code @RestController} 摆进来，
 * 链的形状才和线上一致，坑才露得出来。
 * <p>同理，单元测试（直接调 advice 方法）也发现不了 ——
 * 它根本没经过 Spring 的参数绑定。
 *
 * <p>所以这里锁住三条：
 * <ol>
 *   <li>带 {@link AuditOp} 的接口<b>确实</b>会被切面截到（切点没失效、注解取得到）</li>
 *   <li>方法级授权（{@code @PreAuthorize}）被拒时，切面仍能记到 {@code denied}
 *       —— 这要求切面排在授权拦截器<b>外面</b></li>
 *   <li>方法抛异常时记 {@code failure}</li>
 * </ol>
 */
@SpringBootTest(classes = AuditAspectIntegrationTest.Config.class)
@AutoConfigureMockMvc
@DisplayName("审计切面在真实 Spring 代理上的行为")
class AuditAspectIntegrationTest {

    @Configuration
    @EnableAspectJAutoProxy
    @EnableMethodSecurity
    static class Config {

        /** 只放行 URL 层的权限；方法级的 @PreAuthorize 照常生效（那才是要测的） */
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        @Bean
        AuditService auditService() {
            return mock(AuditService.class);
        }

        @Bean
        AuditHelper auditHelper() {
            return new AuditHelper();
        }

        @Bean
        AuditAspect auditAspect(AuditService auditService, AuditHelper auditHelper) {
            return new AuditAspect(auditService, auditHelper);
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }

        /**
         * 仿生产的 {@code GlobalExceptionHandler}：把异常翻成 500。
         *
         * <p>没有它的话，异常会一路冒到 MockMvc 外面（真实项目里会被
         * {@code GlobalExceptionHandler} 接住变成 500 响应），
         * 测试断言的就不是"客户端看到的那个响应"了。
         */
        @Bean
        ProbeExceptionHandler probeExceptionHandler() {
            return new ProbeExceptionHandler();
        }
    }

    @RestControllerAdvice
    static class ProbeExceptionHandler {
        /** 与生产一致：越权是 403，不是 500 */
        @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
        public ResponseEntity<String> handleDenied(
                org.springframework.security.access.AccessDeniedException e) {
            return ResponseEntity.status(403).body("denied");
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<String> handle(Exception e) {
            return ResponseEntity.status(500).body("boom");
        }
    }

    /** 被审计的替身：三个接口分别对应成功 / 失败 / 拒绝 */
    @RestController
    static class ProbeController {

        @AuditOp(op = AuditOpType.DETAIL, resourceType = "video", resourceId = "#videoId")
        @GetMapping("/probe/detail")
        public String detail(@RequestParam Long videoId) {
            return "ok";
        }

        @AuditOp(op = AuditOpType.DETAIL, resourceType = "video", resourceId = "#videoId")
        @GetMapping("/probe/boom")
        public String boom(@RequestParam Long videoId) {
            throw new IllegalStateException("业务炸了");
        }

        @AuditOp(op = AuditOpType.DELETE, resourceType = "video", resourceId = "#videoId")
        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/probe/restricted")
        public String restricted(@RequestParam Long videoId) {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditService auditService;

    @Test
    @DisplayName("带 @AuditOp 的接口确实被切到，并按注解里的声明落库")
    void annotatedEndpointIsAudited() throws Exception {
        mockMvc.perform(get("/probe/detail").param("videoId", "7"))
            .andExpect(status().isOk());

        verify(auditService).log(any(), eq("detail"), eq("video"), eq(7L),
            eq("success"), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("接口抛异常 ⇒ 记 failure")
    void exceptionIsAuditedAsFailure() throws Exception {
        mockMvc.perform(get("/probe/boom").param("videoId", "9"))
            .andExpect(status().is5xxServerError());

        verify(auditService).log(any(), eq("detail"), eq("video"), eq(9L),
            eq("failure"), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("@PreAuthorize 拒绝 ⇒ 记 denied（切面必须排在授权拦截器外面）")
    @WithMockUser(roles = "USER")
    void authorizationDenialIsAuditedAsDenied() throws Exception {
        // 只有 ROLE_USER，没有 ADMIN ⇒ 方法级授权拦截器会拒绝
        mockMvc.perform(get("/probe/restricted").param("videoId", "11"))
            .andExpect(status().isForbidden());

        verify(auditService).log(any(), eq("delete"), eq("video"), eq(11L),
            eq("denied"), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
