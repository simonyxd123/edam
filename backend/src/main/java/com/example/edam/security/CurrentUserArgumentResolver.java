package com.example.edam.security;

import com.example.edam.security.CurrentUserId;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把 {@link CurrentUserId} 标注的参数解析为 JWT 里的用户 ID。
 *
 * <p>这是把"客户端可伪造的 X-User-Id 头"换成"服务端可信的 JWT principal"
 * 的关键一环 —— 改完之后 controller 方法体一行都不用动，
 * 只要把注解从 {@code @RequestHeader("X-User-Id")} 换成
 * {@code @CurrentUserId}。
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && (parameter.getParameterType() == Long.class
                    || parameter.getParameterType() == long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        CurrentUserId ann = parameter.getParameterAnnotation(CurrentUserId.class);
        boolean required = ann == null || ann.required();

        Long userId = CurrentUser.optionalUserId();
        if (userId == null && required) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或会话已失效");
        }
        return userId;
    }
}
