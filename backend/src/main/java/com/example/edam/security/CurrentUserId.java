package com.example.edam.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入当前登录用户 ID。
 *
 * <p>值来自 JWT（Spring Security 的 SecurityContext），
 * <b>不</b>来自客户端可伪造的 {@code X-User-Id} 请求头。
 *
 * <p>用法：
 * <pre>{@code
 * public void foo(@CurrentUserId Long userId) { ... }              // 未登录 → 401
 * public void bar(@CurrentUserId(required = false) Long userId) { ... } // 未登录 → null
 * }</pre>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {

    /**
     * 未登录时是否允许为 null。
     * false（默认）→ 抛 401；true → 注入 null。
     */
    boolean required() default true;
}
