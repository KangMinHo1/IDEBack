// 경로: src/main/java/com/myide/backend/config/StompWebSocketConfig.java
package com.myide.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class StompWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // 채팅 StompConfig
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 프론트엔드에서 웹소켓 연결을 맺을 엔드포인트
        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns("*"); // CORS 허용
        // .withSockJS(); // 필요시 SockJS 활성화 (최신 @stomp/stompjs는 네이티브 ws를 주로 씁니다)
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 메시지를 구독(수신)하는 요청 엔드포인트 (프론트가 대기하는 곳)
        registry.enableSimpleBroker("/topic");

        // 메시지를 발행(송신)하는 요청 엔드포인트 (프론트가 서버로 보낼 때)
        registry.setApplicationDestinationPrefixes("/app");
    }
}