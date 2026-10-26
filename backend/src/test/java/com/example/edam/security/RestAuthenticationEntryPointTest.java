package com.example.edam.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未认证（401）/ 权限不足（403）时的 Problem Details 响应单测。
 *
 * <p><b>为什么这个类必须存在</b>：Spring Security 在没有显式配置
 * {@code authenticationEntryPoint} 时，默认用的是
 * {@code Http403ForbiddenEntryPoint} —— <b>未认证也返回 403</b>，而且
 * body 是空的。后果有二：
 * <ol>
 *   <li>与 OpenAPI 契约不符（那边声明的是 401 + Error schema）</li>
 *   <li>前端 {@code web/src/api/client.ts} 只在 <b>401</b> 时才去用
 *       refresh_token 换新 token 并重放原请求；403 被当成"权限不足"直接
 *       弹窗 —— 用户明明还有有效 refresh_token，却只能被反复打断</li>
 * </ol>
 */
class RestAuthenticationEntryPointTest {

    private final RestAuthenticationEntryPoint entryPoint =
            new RestAuthenticationEntryPoint(new ObjectMapper());

    @Test
    @DisplayName("未认证返回 401，且 body 是 Problem Details（不是空 body）")
    void unauthenticatedReturns401WithProblemDetails() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest("GET", "/videos"), response,
                new AuthenticationException("未认证") { });

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE),
                "响应必须是 JSON，实际: " + response.getContentType());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(response.getContentAsString(), Map.class);
        assertEquals("Unauthorized", body.get("title"));
        assertEquals(401, body.get("status"));
        assertEquals("AUTH_401", body.get("code"));
        assertTrue(body.containsKey("detail"), "前端靠 detail 显示提示语，不能缺");
        assertTrue(body.containsKey("trace_id"), "OpenAPI 的 Error schema 要求 trace_id");
    }

    @Test
    @DisplayName("已认证但权限不足返回 403，格式与 401 同构")
    void forbiddenReturns403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAccessDeniedHandler(new ObjectMapper())
                .handle(new MockHttpServletRequest("DELETE", "/videos/1"), response,
                        new AccessDeniedException("权限不足"));

        assertEquals(403, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(response.getContentAsString(), Map.class);
        assertEquals("Forbidden", body.get("title"));
        assertEquals("AUTH_403", body.get("code"));
        assertTrue(body.containsKey("trace_id"), "OpenAPI 的 Error schema 要求 trace_id");
    }
}
