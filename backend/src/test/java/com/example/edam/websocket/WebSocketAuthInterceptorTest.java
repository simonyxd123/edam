package com.example.edam.websocket;

import com.example.edam.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link WebSocketAuthInterceptor} 的单测。
 *
 * <p><b>盯的是什么</b>：订阅敏感 topic 时的**权限**校验。
 *
 * <p>原来只校验目的地的**形状**（是不是 {@code /topic/notifications/...}），
 * 不校验角色 —— 于是任何一个登录用户（包括只有 {@code notification:read}
 * 的普通员工）都能订阅 {@code /topic/notifications/leak}，
 * 实时收到他人的疑似泄露用户 ID、泄露文件路径、匹配会话 ID 与置信度
 * （广播的是整个 {@code LeakDetection} 对象，不是脱敏摘要）。
 */
class WebSocketAuthInterceptorTest {

    private static final String SECRET =
        "3f9a1c7e5b2d8046a1f3c9e7d5b2048ac1e7d3b95f204a6c8e1d7b3f5a9c2e604d";

    private JwtTokenProvider jwtTokenProvider;
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenTtlSeconds", 600);
        interceptor = new WebSocketAuthInterceptor(jwtTokenProvider);
    }

    private String tokenWith(List<String> permissions) {
        return jwtTokenProvider.createAccessToken(7L, "sess-1", List.of("employee"), permissions);
    }

    private Message<byte[]> subscribeMessage(String destination, String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("普通员工订阅泄露 topic ⇒ 拒绝（数据不该进普通员工的眼睛）")
    void employeeCannotSubscribeLeakTopic() {
        String token = tokenWith(List.of("notification:read", "dashboard:read"));
        Message<byte[]> msg = subscribeMessage("/topic/notifications/leak", token);
        assertThrows(AccessDeniedException.class,
            () -> interceptor.preSend(msg, null));
    }

    @Test
    @DisplayName("有 leak:read 的审计员 ⇒ 允许订阅泄露 topic")
    void auditorWithLeakReadCanSubscribe() {
        String token = tokenWith(List.of("leak:read", "audit:read"));
        Message<byte[]> msg = subscribeMessage("/topic/notifications/leak", token);
        assertDoesNotThrow(() -> interceptor.preSend(msg, null));
    }

    @Test
    @DisplayName("admin（*:*）⇒ 允许订阅")
    void adminCanSubscribe() {
        String token = tokenWith(List.of("*:*"));
        Message<byte[]> msg = subscribeMessage("/topic/notifications/leak", token);
        assertDoesNotThrow(() -> interceptor.preSend(msg, null));
    }

    @Test
    @DisplayName("非泄露 topic ⇒ 不需要额外权限")
    void ordinaryNotificationTopicNeedsNoSpecialPermission() {
        String token = tokenWith(List.of("notification:read"));
        Message<byte[]> msg = subscribeMessage("/topic/notifications", token);
        assertDoesNotThrow(() -> interceptor.preSend(msg, null));
    }

    @Test
    @DisplayName("形状就不对的 topic ⇒ 拒绝（与权限无关）")
    void disallowedShapeIsRejected() {
        String token = tokenWith(List.of("*:*"));
        Message<byte[]> msg = subscribeMessage("/topic/admin/secrets", token);
        assertThrows(AccessDeniedException.class,
            () -> interceptor.preSend(msg, null));
    }
}
