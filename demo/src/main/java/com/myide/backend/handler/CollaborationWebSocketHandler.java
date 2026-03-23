// 경로: src/main/java/com/myide/backend/handler/CollaborationWebSocketHandler.java
package com.myide.backend.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class CollaborationWebSocketHandler extends BinaryWebSocketHandler {

    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String room = getRoomName(session);
        rooms.computeIfAbsent(room, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("🤝 [Collab] 동시 편집 접속: 세션 ID = {}, 방 = {}", session.getId(), room);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String room = getRoomName(session);
        Set<WebSocketSession> roomSessions = rooms.get(room);

        if (roomSessions != null) {
            for (WebSocketSession s : roomSessions) {
                if (s.isOpen() && !s.getId().equals(session.getId())) {
                    s.sendMessage(message);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String room = getRoomName(session);
        Set<WebSocketSession> roomSessions = rooms.get(room);
        if (roomSessions != null) {
            roomSessions.remove(session);
            if (roomSessions.isEmpty()) {
                rooms.remove(room);
                log.info("💥 [Collab] 빈 방 삭제됨: {}", room);
            }
        }
        log.info("👋 [Collab] 동시 편집 퇴장: 세션 ID = {}, 방 = {}", session.getId(), room);
    }

    // 💡 [핵심 해결] 정확하게 쿼리 파라미터(?room=...)에서 방 이름을 뽑아냅니다!
    private String getRoomName(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            String[] params = uri.getQuery().split("&");
            for (String param : params) {
                if (param.startsWith("room=")) {
                    String rawRoom = param.substring(5);
                    return URLDecoder.decode(rawRoom, StandardCharsets.UTF_8);
                }
            }
        }
        return "default-room";
    }
}