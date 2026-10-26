package com.example.edam.security;

import com.example.edam.exception.ProblemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 已认证但权限不足（403）时的 Problem Details 响应。
 *
 * <p>与 {@link RestAuthenticationEntryPoint} 分开：401 是"没登录"，
 * 403 是"登录了但不许做这件事"。前端 {@code client.ts} 正是按这个
 * 区分处理的 —— 401 触发 refresh_token 续期，403 只弹提示不登出。
 *
 * <p>方法级鉴权（{@code @PreAuthorize}）抛出的 AccessDeniedException
 * 由 {@code GlobalExceptionHandler} 处理（同样的 Problem Details 形状）；
 * 这个 handler 负责的是<b>URL 级</b>鉴权在进入 Controller 之前就拒绝的情况。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        Map<String, Object> body = ProblemDetails.of(
                HttpStatus.FORBIDDEN, "Forbidden", "没有权限执行该操作", "AUTH_403");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
