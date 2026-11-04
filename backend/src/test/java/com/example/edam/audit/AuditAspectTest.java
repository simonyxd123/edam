package com.example.edam.audit;

import com.example.edam.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditAspect} 的单测。
 *
 * <p><b>这个用例锁住的是整套审计里最要紧的一条不变量</b>：
 * <b>成功、失败、拒绝，三种结果都必须留下痕迹。</b>
 *
 * <p>手写 {@code auditService.log(...)} 只能记到"走到那行为止"，
 * 一旦方法在权限校验、参数校验处抛异常，操作就完全没进日志。
 * 而在防泄密系统里，<b>"越权访问未授权视频"恰恰是最该被记下来的一类</b>。
 * 切面就是为了把这个漏洞补上 —— 所以必须有测试盯着它。
 */
class AuditAspectTest {

    private AuditService auditService;
    private AuditAspect aspect;
    private ProceedingJoinPoint pjp;
    private MethodSignature signature;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        // AuditHelper 只做 IP / UA 提取，这里用真对象（它是纯函数式的，无副作用）
        aspect = new AuditAspect(auditService, new com.example.edam.util.AuditHelper());

        pjp = mock(ProceedingJoinPoint.class);
        signature = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(signature);

        request = new MockHttpServletRequest("GET", "/api/v1/videos/7");
        request.setRemoteAddr("10.0.0.9");
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(request, response));

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    // ==================================================================
    // 三种结果
    // ==================================================================

    @Test
    @DisplayName("正常返回 ⇒ success")
    void successIsRecorded() throws Throwable {
        givenMethod("success");

        aspect.around(pjp);

        assertEquals("success", capturedResult());
        assertEquals(7L, capturedResourceId());
        assertEquals("video", capturedResourceType());
    }

    @Test
    @DisplayName("方法抛异常 ⇒ failure，且异常原因进 detail")
    void failureIsRecorded() throws Throwable {
        givenMethod("failure");
        when(pjp.proceed()).thenThrow(new IllegalArgumentException("分类参数非法"));

        try {
            aspect.around(pjp);
        } catch (IllegalArgumentException expected) {
            // 异常必须原样抛出 —— 审计不能改变业务语义
        }

        assertEquals("failure", capturedResult());
        assertTrue(capturedDetail().contains("分类参数非法"),
            "失败原因要进 detail，否则看不出哪一步挂了：" + capturedDetail());
    }

    @Test
    @DisplayName("权限不足（AccessDeniedException）⇒ denied")
    void deniedIsRecorded() throws Throwable {
        givenMethod("denied");
        when(pjp.proceed()).thenThrow(new AccessDeniedException("权限不足"));

        try {
            aspect.around(pjp);
        } catch (AccessDeniedException expected) {
            // 原样抛出
        }

        assertEquals("denied", capturedResult(),
            "越权尝试必须被记成 denied —— 这是防泄密审计最关心的一类");
    }

    @Test
    @DisplayName("方法自己返回 403（不抛异常）⇒ 也算 denied")
    void forbiddenResponseWithoutExceptionIsDenied() throws Throwable {
        // 项目里有一批接口是 return ResponseEntity.status(FORBIDDEN).body(...)
        // 而不是抛异常 —— 只看异常会把它们记成 success
        givenMethod("deniedResponse");
        when(pjp.proceed()).thenReturn(
            org.springframework.http.ResponseEntity.status(403).build());

        aspect.around(pjp);

        assertEquals("denied", capturedResult());
        assertEquals(403, capturedStatusCode());
    }

    // ==================================================================
    // 操作者 / IP / 端点
    // ==================================================================

    @Test
    @DisplayName("操作者取自 SecurityContext（JWT）")
    void userIdComesFromSecurityContext() throws Throwable {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("42", null, List.of()));
        givenMethod("success");

        aspect.around(pjp);

        assertEquals(42L, capturedUserId());
    }

    @Test
    @DisplayName("真实客户端 IP 取 X-Forwarded-For 的第一个（反代后面拿到的都是代理 IP）")
    void clientIpPrefersForwardedFor() throws Throwable {
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        givenMethod("success");

        aspect.around(pjp);

        assertEquals("203.0.113.7", capturedIp());
    }

    @Test
    @DisplayName("命中的是路由模板而不是带真实 ID 的路径")
    void endpointUsesRouteTemplate() throws Throwable {
        request.setAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern",
            "/videos/{video_id}");
        givenMethod("success");

        aspect.around(pjp);

        assertEquals("/videos/{video_id}", capturedEndpoint());
    }

    // ==================================================================
    // 脱敏
    // ==================================================================

    @Test
    @DisplayName("请求参数摘要里的凭据被打码")
    void sensitiveQueryParamsAreMasked() throws Throwable {
        request.setParameter("keyword", "内部培训");
        request.setParameter("token", "eyJhbGciOi.JIUzI1NiJ9.sig");
        givenMethod("withParams");

        aspect.around(pjp);

        String detail = capturedDetail();
        assertTrue(detail.contains("keyword=内部培训"), "普通参数要保留：" + detail);
        assertTrue(detail.contains("token=***"), "token 必须打码：" + detail);
        assertFalse(detail.contains("eyJhbGciOi"), "明文 token 不能进审计：" + detail);
    }

    @Test
    @DisplayName("hideParams ⇒ 整条参数摘要都不落库")
    void hideParamsSuppressesSummary() throws Throwable {
        request.setParameter("keyword", "内部培训");
        givenMethod("hiddenParams");

        aspect.around(pjp);

        assertFalse(capturedDetail().contains("keyword"),
            "hideParams 的接口不该有参数摘要：" + capturedDetail());
    }

    // ==================================================================
    // auditSuccess=false（高频接口只记失败）
    // ==================================================================

    @Test
    @DisplayName("auditSuccess=false 时成功不记，失败要记")
    void auditSuccessFalseSkipsSuccess() throws Throwable {
        givenMethod("skipSuccess");

        aspect.around(pjp);

        verify(auditService, never()).log(anyLong(), anyString(), anyString(),
            any(), anyString(), any(), any(), any(),
            any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("auditSuccess=false 时失败仍然要记")
    void auditSuccessFalseStillRecordsFailure() throws Throwable {
        givenMethod("skipSuccess");
        when(pjp.proceed()).thenThrow(new IllegalStateException("MinIO 连不上"));

        try {
            aspect.around(pjp);
        } catch (IllegalStateException expected) {
            // 原样抛出
        }

        assertEquals("failure", capturedResult());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 让连接点看起来像在调用 fixture 里的某个方法。
     *
     * <p>切面现在自己从方法签名上取 {@link AuditOp}（不再靠 Spring 绑参数），
     * 所以测试必须把"被调用的方法"喂给它 —— 这也正是
     * {@link AuditAspectIntegrationTest} 要在真实代理上再验一遍的原因。
     */
    private void givenMethod(String fixtureMethod) throws Throwable {
        Method method = methodOf(fixtureMethod);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"videoId"});
        when(pjp.getArgs()).thenReturn(new Object[]{7L});
        when(pjp.proceed()).thenReturn("ok");
    }

    /** 从被测 fixture 类里取出某个方法 */
    private Method methodOf(String fixtureMethod) {
        for (Method m : Fixture.class.getDeclaredMethods()) {
            if (m.getName().equals(fixtureMethod)) {
                return m;
            }
        }
        throw new IllegalArgumentException("fixture 方法不存在: " + fixtureMethod);
    }

    private ArgumentCaptor<String> resultCaptor;

    private String capturedResult() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(any(), anyString(), anyString(), any(),
            captor.capture(), any(), any(), any(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    private String capturedDetail() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(any(), anyString(), anyString(), any(), anyString(),
            captor.capture(), any(), any(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    private Long capturedUserId() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(auditService).log(captor.capture(), anyString(), anyString(), any(),
            anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    private Long capturedResourceId() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(auditService).log(any(), anyString(), anyString(), captor.capture(),
            anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    private String capturedResourceType() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(any(), anyString(), captor.capture(), any(),
            anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    private String capturedIp() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(any(), anyString(), anyString(), any(), anyString(),
            any(), captor.capture(), any(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    private String capturedEndpoint() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        // 参数顺序：userId, op, resourceType, resourceId, result, detail, ip, userAgent,
        //           httpMethod, endpoint, statusCode, durationMs, sessionId
        verify(auditService).log(any(), anyString(), anyString(), any(), anyString(),
            any(), any(), any(), any(), captor.capture(), any(), any(), any());
        return captor.getValue();
    }

    private Integer capturedStatusCode() {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(auditService).log(any(), anyString(), anyString(), any(), anyString(),
            any(), any(), any(), any(), any(), captor.capture(), any(), any());
        return captor.getValue();
    }

    /** @AuditOp 的测试载体（方法体不会被执行，只取注解） */
    static class Fixture {
        @AuditOp(op = AuditOpType.DETAIL, resourceType = "video", resourceId = "#videoId")
        void success(Long videoId) {}

        @AuditOp(op = AuditOpType.DETAIL, resourceType = "video", resourceId = "#videoId")
        void failure(Long videoId) {}

        @AuditOp(op = AuditOpType.DETAIL, resourceType = "video", resourceId = "#videoId")
        void denied(Long videoId) {}

        @AuditOp(op = AuditOpType.DETAIL, resourceType = "video", resourceId = "#videoId")
        void deniedResponse(Long videoId) {}

        @AuditOp(op = AuditOpType.LIST, resourceType = "video")
        void withParams(Long videoId) {}

        @AuditOp(op = AuditOpType.LOGIN, resourceType = "auth", hideParams = true)
        void hiddenParams(Long videoId) {}

        @AuditOp(op = AuditOpType.PLAY, resourceType = "video", resourceId = "#videoId",
            auditSuccess = false)
        void skipSuccess(Long videoId) {}
    }
}
