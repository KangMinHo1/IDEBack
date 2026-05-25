package com.myide.backend.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
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
    private final WebSocketSessionAuthRegistry sessionAuthRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            handleConnect(accessor);
        }

        if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            handleDisconnect(accessor);
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        String token = resolveToken(accessor);

        if (!StringUtils.hasText(token)) {
            log.warn("[WebSocket Auth] Authorization header is missing. sessionId={}", sessionId);
            return;
        }

        if (!jwtProvider.validateAccessToken(token)) {
            log.warn("[WebSocket Auth] Invalid ACCESS token. sessionId={}", sessionId);
            throw new AccessDeniedException("Invalid WebSocket access token.");
        }

        Long userId = jwtProvider.getUserIdFromToken(token);

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) {
            sessionAttributes = new ConcurrentHashMap<>();
            accessor.setSessionAttributes(sessionAttributes);
        }

        sessionAttributes.put(AUTH_USER_ID, userId);
        accessor.setUser(() -> String.valueOf(userId));

        sessionAuthRegistry.register(sessionId, userId);

        log.info(
                "[WebSocket Auth] STOMP CONNECT authenticated. sessionId={}, userId={}",
                sessionId,
                userId
        );
    }

    private void handleDisconnect(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();

        sessionAuthRegistry.remove(sessionId);

        log.debug("[WebSocket Auth] STOMP DISCONNECT. sessionId={}", sessionId);
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String token = firstNativeHeader(accessor, "Authorization");

        if (!StringUtils.hasText(token)) {
            token = firstNativeHeader(accessor, "authorization");
        }

        if (!StringUtils.hasText(token)) {
            token = firstNativeHeader(accessor, "accessToken");
        }

        if (!StringUtils.hasText(token)) {
            token = firstNativeHeader(accessor, "token");
        }

        if (!StringUtils.hasText(token)) {
            return null;
        }

        if (token.startsWith("Bearer ")) {
            return token.substring(7);
        }

        return token;
    }

    private String firstNativeHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);

        if (values == null || values.isEmpty()) {
            return null;
        }

        return values.get(0);
    }
}