package com.myide.backend.security;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebSocketSessionAuthRegistry {

    private final ConcurrentMap<String, Long> sessionUserMap = new ConcurrentHashMap<>();

    public void register(String sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return;
        }

        sessionUserMap.put(sessionId, userId);
    }

    public Optional<Long> getUserId(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(sessionUserMap.get(sessionId));
    }

    public void remove(String sessionId) {
        if (sessionId == null) {
            return;
        }

        sessionUserMap.remove(sessionId);
    }
}