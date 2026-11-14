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
        // AuditHelper 只做 IP / UA 提取，这里用真对象（它是纯函数式的，无副作用）。
        // 受信代理名单覆盖测试里的对端地址（10.0.0.9），这样转发头才会被采信。
        aspect = new AuditAspect(auditService, new com.example.edam.util.AuditHelper(
            mock(AuditService.class),
            new com.example.edam.security.ClientIpResolver("10.0.0.0/8")));

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
    // detail 模板求值
    // ==================================================================

    @Test
    @DisplayName("detail 的 k=v 模板要真求值出来（整串丢给 SpEL 会被当成赋值而失败）")
    void detailTemplateIsEvaluated() throws Throwable {
        // 回归锁：detail 曾被整串丢进 SpEL.parseExpression()，
        // 而 SpEL 把 '=' 当**赋值运算符** ⇒ "employee_no=#request.employeeNo"
        // 报 EL1009E「cannot be set on null」、带逗号的报 EL1041E。
        // 结果是全仓 40 多处 @AuditOp(detail=...) 的补充说明**全部为空**。
        givenMethod("withDetail");

        aspect.around(pjp);

        String detail = capturedDetail();
        assertTrue(detail.contains("level=L2"),
            "detail 的值必须求值成实际值，而不是原文或空白：" + detail);
        assertFalse(detail.contains("#level"),
            "求值成功后不该再残留表达式原文：" + detail);
    }

    @Test
    @DisplayName("detail 里引用不到的变量保留原文，不整段丢弃")
    void detailKeepsUnresolvableValue() throws Throwable {
        givenMethod("withDetailUnknownVar");

        aspect.around(pjp);

        String detail = capturedDetail();
        assertTrue(detail.contains("who=#noSuchVar"),
            "求不出值时保留原文，好过留白让人看不出这里本该有内容：" + detail);
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
    @DisplayName("真实客户端 IP 从 X-Forwarded-For 里往左找（跳过中间那几跳代理）")
    void clientIpPrefersForwardedFor() throws Throwable {
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        givenMethod("success");

        aspect.around(pjp);

        assertEquals("203.0.113.7", capturedIp());
    }

    @Test
    @DisplayName("客户端自己伪造的 XFF 前缀不作数（代理追加的那一段才是真的）")
    void clientIpIgnoresForgedForwardedForPrefix() throws Throwable {
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");
        givenMethod("success");

        aspect.around(pjp);

        assertEquals("203.0.113.7", capturedIp());
    }

    @Test
    @DisplayName("超长的伪造 XFF 不会进审计（否则整条记录写不进 VARCHAR(45)）")
    void clientIpDropsOversizedForwardedFor() throws Throwable {
        request.addHeader("X-Forwarded-For", "9".repeat(500));
        givenMethod("success");

        aspect.around(pjp);

        assertEquals("10.0.0.9", capturedIp());
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
        // 参数名与实参按方法真实签名给 —— SpEL 变量（#videoId / #level）
        // 就是按参数名绑定的，写死一份会让多参数的 fixture 取不到值。
        Class<?>[] types = method.getParameterTypes();
        String[] names = new String[types.length];
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = i == 0 ? "videoId" : "level";
            args[i] = i == 0 ? 7L : "L2";
        }
        when(signature.getParameterNames()).thenReturn(names);
        when(pjp.getArgs()).thenReturn(args);
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

        /** detail 里的值来自方法参数（#level 对应参数 level） */
        @AuditOp(op = AuditOpType.UPDATE, resourceType = "video", resourceId = "#videoId",
            detail = "level=#level")
        void withDetail(Long videoId, String level) {}

        /** detail 里引用了不存在的变量 —— 求值失败应保留原文 */
        @AuditOp(op = AuditOpType.UPDATE, resourceType = "video", resourceId = "#videoId",
            detail = "who=#noSuchVar")
        void withDetailUnknownVar(Long videoId) {}
    }
}
