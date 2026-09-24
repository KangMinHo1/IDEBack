package com.myide.backend.config;
import com.myide.backend.security.WebSocketAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class StompWebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final WebSocketAuthChannelInterceptor webSocketAuthChannelInterceptor;

    /* 예전에는 "*" 라서 어느 사이트에서든 접속할 수 있었다. 연결 뒤 메시지 단계는
     * webSocketAuthChannelInterceptor 가 토큰으로 막아 주지만, 접속 자체를 열어 둘 이유가 없어
     * WebSocketConfig / SecurityConfig 와 같은 목록으로 좁힌다. 원본은 application.yml 한 곳이다. */
    @Value("${app.cors.allowed-origins}")
    private String[] allowedOriginPatterns;

    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat").setAllowedOriginPatterns(allowedOriginPatterns);
        registry.addEndpoint("/ws/webrtc").setAllowedOriginPatterns(allowedOriginPatterns);
        registry.addEndpoint("/ws/presence").setAllowedOriginPatterns(allowedOriginPatterns);
        registry.addEndpoint("/ws/messages").addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes) {
                attributes.put("DM_ONLY",true); return true;
            }
            @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception) {}
        }).setAllowedOriginPatterns(allowedOriginPatterns);
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic","/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthChannelInterceptor);
    }
}