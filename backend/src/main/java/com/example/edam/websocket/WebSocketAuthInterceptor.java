package com.example.edam.websocket;

import com.example.edam.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket STOMP 入站鉴权拦截器。
 *
 * <p><b>为什么必须有</b>：STOMP 的 CONNECT 帧自带鉴权头，但 Spring 的
 * {@code JwtAuthenticationFilter} 是 Servlet Filter —— 它在 HTTP 握手层
 * 生效，对 {@code /ws} 的 HTTP 升级请求会跑一次，但**之后建立的
 * STOMP 会话（CONNECT 帧及其后的 SUBSCRIBE）完全不过那个过滤器**。
 * 所以 WS 通道上必须自己校验。
 *
 * <p>被拦的场景：未带 token / token 无效 → 直接抛异常，连接被关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 只允许订阅通知类 topic。
     *
     * <p>{@code /topic/notifications/leak} 广播的是泄露检测告警，
     * 含 user_id、泄露文件路径、匹配置信度 —— 只有管理员该看。
     */
    private static final List<Pattern> ALLOWED_SUBSCRIPTIONS = List.of(
        Pattern.compile("^/topic/notifications(/[a-zA-Z0-9_-]+)*$")
    );

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
            message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        switch (command) {
            case CONNECT:
                requireValidToken(accessor);
                // 认证通过后把 userId 放进 session 属性，后续帧可用
                Long userId = resolveUserId(accessor);
                accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    String.valueOf(userId), null, java.util.List.of()));
                break;
            case SUBSCRIBE:
                requireValidToken(accessor);
                requireAllowedSubscription(accessor);
                break;
            case SEND:
            case UNSUBSCRIBE:
                requireValidToken(accessor);
                break;
            default:
                break;
        }
        return message;
    }

    private void requireValidToken(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);
        if (token == null || token.isBlank()) {
            log.warn("ws_auth_rejected, reason=missing_token, command={}",
                accessor.getCommand());
            throw new org.springframework.security.access.AccessDeniedException(
                "WebSocket 未认证：缺少 Authorization 头");
        }
        try {
            jwtTokenProvider.parseAndValidate(token);
        } catch (Exception e) {
            log.warn("ws_auth_rejected, reason=invalid_token, command={}, error={}",
                accessor.getCommand(), e.getMessage());
            throw new org.springframework.security.access.AccessDeniedException(
                "WebSocket 未认证：token 无效或已过期");
        }
    }

    private void requireAllowedSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        for (Pattern p : ALLOWED_SUBSCRIPTIONS) {
            Matcher m = p.matcher(destination);
            if (m.matches()) {
                return;
            }
        }
        log.warn("ws_subscribe_rejected, destination={}", destination);
        throw new org.springframework.security.access.AccessDeniedException(
            "不允许订阅该 topic: " + destination);
    }

    private Long resolveUserId(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);
        if (token == null) {
            return -1L;
        }
        try {
            return Long.valueOf(jwtTokenProvider.parseAndValidate(token).getSubject());
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * 取 token：优先 Authorization 头（Bearer），其次 query 参数
     * {@code token=}（浏览器 WebSocket API 不能自定义头，
     * 前端常用 query 传 token）。
     */
    private String extractToken(StompHeaderAccessor accessor) {
        List<String> headers = accessor.getNativeHeader("Authorization");
        if (headers != null && !headers.isEmpty()) {
            String h = headers.get(0);
            if (h != null && h.startsWith("Bearer ")) {
                return h.substring("Bearer ".length()).trim();
            }
        }
        String queryToken = accessor.getFirstNativeHeader("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken.trim();
        }
        return null;
    }
}
