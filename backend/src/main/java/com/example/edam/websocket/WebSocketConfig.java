package com.example.edam.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket STOMP 配置（v3.3 W-6.4 实时通知）
 *
 * 启用 STOMP 消息代理，让 SimpMessagingTemplate bean 可注入。
 * 客户端连接 endpoint: /ws
 * 订阅前缀: /topic（服务端推送），发送前缀: /app（客户端上行）
 *
 * <p><b>鉴权</b>：CONNECT / SEND / SUBSCRIBE 都过
 * {@link WebSocketAuthInterceptor} 校验 JWT。
 * 原实现把 {@code /ws/**} 设为 permitAll 且
 * {@code setAllowedOriginPatterns("*")}，任何来源的任何页面都能连上
 * 并订阅 {@code /topic/notifications/leak}，实时收到含 user_id、
 * 泄露文件路径、匹配置信度的告警。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * 允许的跨域来源，逗号分隔。留空则只允许同源（fail-closed）。
     *
     * <p>WebSocket 的 Origin 头**不能**像普通 HTTP 那样靠 CORS 兜底 ——
     * 浏览器发起的 WS 连接会带 Origin，服务端必须自己校验。
     */
    @Value("${edam.websocket.allowed-origins:}")
    private String allowedOrigins;

    private final WebSocketAuthInterceptor authInterceptor;

    public WebSocketConfig(WebSocketAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 简单内存 broker（生产应换 RabbitMQ / Redis STOMP relay）
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 只注册一次。
        // 原实现注册了两次同一个 path：第二次 .withSockJS() 会**覆盖**第一次
        // （StompEndpointRegistry 用 path 作 key 存 LinkedHashMap），
        // 原生 WebSocket 端点静默丢失，只能走 SockJS。
        org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration endpoint =
            registry.addEndpoint("/ws");
        String[] origins = splitOrigins();
        if (origins.length > 0) {
            endpoint.setAllowedOriginPatterns(origins);
        } else {
            // 没配 allowed-origins → 不调 setAllowedOriginPatterns，
            // Spring 默认只允许同源 —— 比 "*" 安全
            endpoint.setAllowedOrigins();
        }
    }

    /**
     * 入站通道鉴权：CONNECT / SEND / SUBSCRIBE / UNSUBSCRIBE 全部拦截。
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    private String[] splitOrigins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    }
}
