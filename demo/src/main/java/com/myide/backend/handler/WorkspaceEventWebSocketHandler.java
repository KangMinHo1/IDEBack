package com.myide.backend.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.ide.FileRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceEventWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String room = getRoomName(session);

        rooms.computeIfAbsent(room, k -> new CopyOnWriteArraySet<>()).add(session);

        log.info("📁 [WorkspaceEvent] 접속: sessionId={}, room={}", session.getId(), room);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String room = getRoomName(session);
        Set<WebSocketSession> sessions = rooms.get(room);

        if (sessions != null) {
            sessions.remove(session);

            if (sessions.isEmpty()) {
                rooms.remove(room);
                log.info("💥 [WorkspaceEvent] 빈 방 삭제됨: {}", room);
            }
        }

        log.info("👋 [WorkspaceEvent] 퇴장: sessionId={}, room={}", session.getId(), room);
    }

    public void broadcastFileTreeChanged(FileRequest request, String action) {
        String branchName = normalizeBranchName(request.getBranchName());

        String room = buildRoomName(
                request.getWorkspaceId(),
                request.getProjectName(),
                branchName
        );

        FileTreeChangedMessage message = new FileTreeChangedMessage(
                "FILE_TREE_CHANGED",
                action,
                request.getWorkspaceId(),
                request.getProjectName(),
                branchName,
                request.getFilePath(),
                request.getType(),
                request.getNewName()
        );

        broadcast(room, message);
    }

    private void broadcast(String room, Object payload) {
        Set<WebSocketSession> sessions = rooms.get(room);

        if (sessions == null || sessions.isEmpty()) {
            log.info("📭 [WorkspaceEvent] 받을 세션 없음: room={}", room);
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(json);

            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }

            log.info("📢 [WorkspaceEvent] broadcast 완료: room={}, payload={}", room, json);

        } catch (Exception e) {
            log.error("❌ [WorkspaceEvent] broadcast 실패: room={}", room, e);
        }
    }

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

    public static String buildRoomName(String workspaceId, String projectName, String branchName) {
        return "workspace:" + workspaceId + ":project:" + projectName + ":branch:" + branchName;
    }

    private String normalizeBranchName(String branchName) {
        if (branchName == null || branchName.isBlank()) {
            return "master";
        }

        return branchName;
    }

    @Getter
    private static class FileTreeChangedMessage {
        private final String type;
        private final String action;
        private final String workspaceId;
        private final String projectName;
        private final String branchName;
        private final String filePath;
        private final String fileType;
        private final String newName;

        public FileTreeChangedMessage(
                String type,
                String action,
                String workspaceId,
                String projectName,
                String branchName,
                String filePath,
                String fileType,
                String newName
        ) {
            this.type = type;
            this.action = action;
            this.workspaceId = workspaceId;
            this.projectName = projectName;
            this.branchName = branchName;
            this.filePath = filePath;
            this.fileType = fileType;
            this.newName = newName;
        }
    }
}