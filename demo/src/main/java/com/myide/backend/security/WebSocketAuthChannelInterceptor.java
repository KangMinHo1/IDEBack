package com.myide.backend.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    public static final String AUTH_USER_ID = "AUTH_USER_ID";

    private final JwtProvider jwtProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            handleConnect(accessor);
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String token = resolveToken(accessor);

        /*
         * 토큰이 아예 없는 경우는 여기서 연결을 끊지 않습니다.
         * 대신 WebRtcController에서 AUTH_USER_ID가 없으면 WebRTC 요청을 거부합니다.
         *
         * 이유:
         * - 같은 STOMP 설정을 채팅에서도 사용할 수 있음
         * - 기존 채팅 기능이 토큰 없이 동작 중이라면 깨질 수 있음
         */
        if (!StringUtils.hasText(token)) {
            log.debug("[WebSocket Auth] Authorization header is missing.");
            return;
        }

        if (!jwtProvider.validateToken(token)) {
            throw new AccessDeniedException("Invalid WebSocket JWT token.");
        }

        Long userId = jwtProvider.getUserIdFromToken(token);

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) {
            sessionAttributes = new ConcurrentHashMap<>();
            accessor.setSessionAttributes(sessionAttributes);
        }

        sessionAttributes.put(AUTH_USER_ID, userId);

        /*
         * accessor.getUser()로도 userId를 참조할 수 있게 설정합니다.
         */
        accessor.setUser(() -> String.valueOf(userId));

        log.info("[WebSocket Auth] STOMP CONNECT authenticated. userId={}", userId);
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        List<String> authorizationHeaders = accessor.getNativeHeader("Authorization");

        if (authorizationHeaders == null || authorizationHeaders.isEmpty()) {
            return null;
        }

        String bearerToken = authorizationHeaders.get(0);

        if (!StringUtils.hasText(bearerToken)) {
            return null;
        }

        if (bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }

        return bearerToken;
    }
}