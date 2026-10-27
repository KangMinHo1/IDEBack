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
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
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

        // 한 세션에 두 스레드가 동시에 보내면 Tomcat 이 IllegalStateException 을 던진다.
        // 파일 생성 알림과 작성자 상태 알림이 겹치면 생성 알림이 사라져 팀원 탐색기에 파일이 안 떴다.
        // 데코레이터가 동시 전송을 줄 세운다(보내기 10초·버퍼 512KB 를 넘기면 그 세션만 끊는다).
        rooms.computeIfAbsent(room, k -> new CopyOnWriteArraySet<>())
                .add(new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024));

        log.info("📁 [WorkspaceEvent] 접속: sessionId={}, room={}", session.getId(), room);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String room = getRoomName(session);
        Set<WebSocketSession> sessions = rooms.get(room);

        if (sessions != null) {
            // 방에는 데코레이터가 들어 있어 원본 세션으로는 지워지지 않는다. id 로 찾는다.
            sessions.removeIf(s -> s.getId().equals(session.getId()));

            if (sessions.isEmpty()) {
                rooms.remove(room);
                log.info("💥 [WorkspaceEvent] 빈 방 삭제됨: {}", room);
            }
        }

        log.info("👋 [WorkspaceEvent] 퇴장: sessionId={}, room={}", session.getId(), room);
    }

    /*
     * 기존 코드 호환용 메서드
     *
     * 기존 파일 생성/삭제/저장 코드가
     * broadcastFileTreeChanged(request, action)
     * 형태로 호출하고 있어도 그대로 동작하게 둡니다.
     */
    public void broadcastFileTreeChanged(FileRequest request, String action) {
        broadcastFileTreeChanged(request, action, null);
    }

    /*
     * 새 메서드
     *
     * sandbox 병합처럼 master 내용이 바뀌고,
     * 협업 room까지 새로 잡아야 하는 경우 revision을 같이 보냅니다.
     */
    public void broadcastFileTreeChanged(FileRequest request, String action, String revision) {
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
                request.getNewName(),
                revision
        );

        broadcast(room, message);
    }

    /*
     * 브랜치가 생기거나 사라졌음을 같은 프로젝트를 보는 모두에게 알린다.
     *
     * 방 이름에는 브랜치가 들어 있다. 그래서 파일 이벤트처럼 한 방에만
     * 보내면 정작 다른 브랜치를 보고 있는 사람에게는 닿지 않는다. 브랜치
     * 목록은 어느 브랜치에 있든 같은 것을 봐야 하므로, 같은 워크스페이스와
     * 프로젝트에 속한 방 전체로 보낸다.
     */
    public void broadcastBranchChanged(
            String workspaceId,
            String projectName,
            String action,
            String branchName
    ) {
        String roomPrefix = "workspace:" + workspaceId + ":project:" + projectName + ":branch:";

        BranchChangedMessage message = new BranchChangedMessage(
                "BRANCH_CHANGED",
                action,
                workspaceId,
                projectName,
                branchName
        );

        for (String room : rooms.keySet()) {
            if (room.startsWith(roomPrefix)) {
                broadcast(room, message);
            }
        }
    }

    /*
     * 한 브랜치를 보고 있는 사람들에게 임의의 메시지를 보낸다.
     *
     * 안전 실행의 작성자 상태·정상 버전 갱신 알림이 쓴다. 메시지에는 반드시
     * type 필드가 있어야 프론트가 파일 트리 이벤트와 구분할 수 있다.
     */
    public void broadcastToBranch(
            String workspaceId,
            String projectName,
            String branchName,
            Object payload
    ) {
        broadcast(buildRoomName(workspaceId, projectName, normalizeBranchName(branchName)), payload);
    }

    private void broadcast(String room, Object payload) {
        Set<WebSocketSession> sessions = rooms.get(room);

        if (sessions == null || sessions.isEmpty()) {
            log.info("📭 [WorkspaceEvent] 받을 세션 없음: room={}", room);
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("❌ [WorkspaceEvent] 메시지 직렬화 실패: room={}", room, e);
            return;
        }

        TextMessage textMessage = new TextMessage(json);

        // 실패는 세션마다 따로 잡는다. 반복문 전체를 감싸면 한 명이 실패할 때
        // 그 뒤의 팀원 모두가 알림을 못 받는다.
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }

            try {
                session.sendMessage(textMessage);
            } catch (Exception e) {
                log.error("❌ [WorkspaceEvent] 전송 실패: room={}, sessionId={}", room, session.getId(), e);
            }
        }

        log.info("📢 [WorkspaceEvent] broadcast 완료: room={}, payload={}", room, json);
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
    private static class BranchChangedMessage {
        private final String type;
        private final String action;
        private final String workspaceId;
        private final String projectName;
        private final String branchName;

        public BranchChangedMessage(
                String type,
                String action,
                String workspaceId,
                String projectName,
                String branchName
        ) {
            this.type = type;
            this.action = action;
            this.workspaceId = workspaceId;
            this.projectName = projectName;
            this.branchName = branchName;
        }
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
        private final String revision;

        public FileTreeChangedMessage(
                String type,
                String action,
                String workspaceId,
                String projectName,
                String branchName,
                String filePath,
                String fileType,
                String newName,
                String revision
        ) {
            this.type = type;
            this.action = action;
            this.workspaceId = workspaceId;
            this.projectName = projectName;
            this.branchName = branchName;
            this.filePath = filePath;
            this.fileType = fileType;
            this.newName = newName;
            this.revision = revision;
        }
    }
}