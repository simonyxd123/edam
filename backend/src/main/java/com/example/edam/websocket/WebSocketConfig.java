package com.example.edam.websocket;

import org.springframework.context.annotation.Configuration;
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
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 简单内存 broker（生产应换 RabbitMQ / Redis STOMP relay）
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 注册 STOMP endpoint，CORS 允许所有来源（生产应收敛）
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*");
        // 也支持 SockJS（老浏览器兼容）
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS();
    }
}