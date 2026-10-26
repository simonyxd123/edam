package com.example.edam.security;

import com.example.edam.exception.ProblemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 未认证（401）时的 Problem Details 响应。
 *
 * <p><b>为什么不能是 403</b>：Spring Security 在没有显式配置
 * {@code authenticationEntryPoint} 时，默认用的是
 * {@code Http403ForbiddenEntryPoint} —— 也就是<b>未认证也返回 403</b>，
 * 而且 body 是空的。这带来三个后果：
 * <ol>
 *   <li><b>与 OpenAPI 契约不符</b>：{@code doc/openapi.yaml} 里所有受保护
 *       端点声明的是 {@code 401 Unauthorized}（{@code Error} schema 必填
 *       type/title/status/trace_id），实现却给 403 空 body</li>
 *   <li><b>前端的 refresh_token 流程永远不触发</b>：
 *       {@code web/src/api/client.ts} 只在 <b>401</b> 时去用 refresh_token
 *       换新 token 并重放原请求；403 被当成"权限不足"，直接弹
 *       "没有权限执行该操作" —— 用户明明还有有效的 refresh_token，
 *       却只能被反复弹窗卡住。access_token 只有 10 分钟 TTL，
 *       也就是说<b>每个用户每 10 分钟都会撞上一次</b></li>
 *   <li><b>前端拿不到 detail</b>：空 body ⇒ {@code data?.detail} 为空，
 *       提示语退化成一句没信息量的"请求失败"</li>
 * </ol>
 *
 * <p>注意"已认证但权限不足"仍是 403（由
 * {@code GlobalExceptionHandler} 的 {@code @PreAuthorize} 分支处理），
 * 语义不变：<b>401 = 没登录，403 =  logged in 但不许做这件事</b>。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Map<String, Object> body = ProblemDetails.of(
                HttpStatus.UNAUTHORIZED, "Unauthorized", "未登录或会话已失效", "AUTH_401");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
