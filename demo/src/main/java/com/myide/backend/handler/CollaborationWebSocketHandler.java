// 경로: src/main/java/com/myide/backend/handler/CollaborationWebSocketHandler.java
package com.myide.backend.handler;

import com.myide.backend.config.WebSocketConfig;
import com.myide.backend.security.CollabHandshakeInterceptor;
import com.myide.backend.security.CollabRoomName;
import com.myide.backend.service.saferun.CollabEditListener;
import com.myide.backend.service.saferun.YjsMessageKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 방마다 보관하는 문서 상태. base64 로 인코딩된 Yjs 업데이트다.
     *
     * 협업 서버는 중계만 하므로 방이 비면 문서도 사라진다. 그래서 파일을
     * 열 때마다 누군가 디스크 내용을 문서에 넣어 줘야 한다. 예전에는 그
     * "누가 넣을지"를 서버가 허락으로 정했는데, 허락받은 사람이 넣기 전에
     * 나가 버리거나 재접속하면 아무도 넣지 못해 그 파일이 빈 채로 열렸다.
     *
     * 그래서 허락 대신 <b>내용 자체를 주고받는다.</b> 처음 도착한 것만
     * 채택하고 나머지에게는 채택된 것을 돌려주므로, 경쟁에서 져도 쓸 것이
     * 있고 같은 내용이 두 번 들어가지도 않는다. 설계 문서가 쓰는 방식과
     * 같고, 코드 파일은 디스크가 원본이라 여기서는 메모리에만 둔다.
     *
     * 서버는 이 문자열을 해석하지 않는다.
     */
    private final Map<String, String> roomDocs = new ConcurrentHashMap<>();

    /**
     * 편집이 일어났음을 듣는 쪽(안전 실행의 편집 장부).
     *
     * 생성자가 아니라 세터로 받는 이유는, 이 핸들러가 듣는 쪽 없이도 중계기로서
     * 온전히 동작해야 하기 때문이다(테스트도 그렇게 만든다).
     */
    private CollabEditListener editListener;

    @Autowired(required = false)
    public void setEditListener(CollabEditListener editListener) {
        this.editListener = editListener;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 컨테이너 기본값과 별개로 스프링 쪽 세션 한도가 따로 걸린다.
        // 한쪽만 올려 두면 큰 변경이 여전히 막힌다.
        session.setBinaryMessageSizeLimit(WebSocketConfig.MAX_MESSAGE_BYTES);
        session.setTextMessageSizeLimit(WebSocketConfig.MAX_MESSAGE_BYTES);

        String room = getRoomName(session);
        rooms.computeIfAbsent(room, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("🤝 [Collab] 동시 편집 접속: 세션 ID = {}, 방 = {}", session.getId(), room);
    }

    /**
     * 전송에 실패해도 방 전체를 멈추지 않는다.
     *
     * 한 사람의 연결이 막혔다고 나머지에게도 안 보내면, 한 명 때문에 팀
     * 전체의 동시 편집이 멈춘다. 실패한 연결은 어차피 곧 정리된다.
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("⚠️ [Collab] 전송 오류: 세션 ID = {}, 이유 = {}",
                session.getId(), exception.getMessage());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String room = getRoomName(session);
        Set<WebSocketSession> roomSessions = rooms.get(room);

        notifyEdit(session, room, message);

        if (roomSessions != null) {
            for (WebSocketSession s : roomSessions) {
                if (s.isOpen() && !s.getId().equals(session.getId())) {
                    s.sendMessage(message);
                }
            }
        }
    }

    /**
     * 코드 파일을 고친 메시지면 "누가 어느 파일을 고쳤는지"를 알린다.
     *
     * 내용은 여전히 해석하지 않는다. 메시지 앞의 종류 표시와, 접속할 때 확인해 둔
     * 회원번호만 본다. 회원번호가 없는 접속(인증을 끈 경우)은 누구인지 알 수 없으므로
     * 알리지 않는다.
     *
     * 여기서 무슨 일이 나도 중계는 계속돼야 한다. 그래서 예외를 삼킨다.
     */
    private void notifyEdit(WebSocketSession session, String room, BinaryMessage message) {
        if (editListener == null) {
            return;
        }

        try {
            if (!YjsMessageKind.isDocumentEdit(message.getPayload())) {
                return;
            }

            CollabRoomName.FileRoom fileRoom = CollabRoomName.parseFileRoom(room);
            Object userId = session.getAttributes().get(CollabHandshakeInterceptor.ATTR_USER_ID);

            if (fileRoom == null || !(userId instanceof Long authorId)) {
                return;
            }

            editListener.onEdit(
                    fileRoom.workspaceId(),
                    fileRoom.projectName(),
                    fileRoom.branchName(),
                    fileRoom.filePath(),
                    authorId
            );
        } catch (RuntimeException e) {
            log.warn("⚠️ [Collab] 편집 기록 실패: 방 = {}, 이유 = {}", room, e.getMessage());
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

                // 방이 비면 보관하던 문서도 버린다. 디스크 파일이 원본이므로
                // 다시 열 때 거기서 새로 만들면 된다.
                roomDocs.remove(room);
                log.info("💥 [Collab] 빈 방 삭제됨: {}", room);
            }
        }
        log.info("👋 [Collab] 동시 편집 퇴장: 세션 ID = {}, 방 = {}", session.getId(), room);
    }

    /** 이 방에 보관 중인 문서 상태. 없으면 null. */
    public String getDoc(String room) {
        if (room == null || room.isBlank()) {
            return null;
        }

        return roomDocs.get(room);
    }

    /**
     * 이 방의 최초 문서를 등록한다.
     *
     * 처음 도착한 것만 채택하고, 진 쪽에는 이미 채택된 것을 돌려준다.
     * 그래야 진 쪽도 빈 문서에 갇히지 않고 채택된 것을 그대로 쓸 수 있다.
     */
    public SeedOutcome seedDoc(String room, String yjsUpdate) {
        if (room == null || room.isBlank() || yjsUpdate == null || yjsUpdate.isBlank()) {
            return new SeedOutcome(false, null);
        }

        String existing = roomDocs.putIfAbsent(room, yjsUpdate);

        if (existing == null) {
            log.info("🌱 [Collab] 최초 문서 등록: 방 = {}", room);
            return new SeedOutcome(true, yjsUpdate);
        }

        return new SeedOutcome(false, existing);
    }

    /**
     * 저장 담당자가 올리는 최신 문서 상태. 마지막에 쓴 것이 남는다.
     *
     * 뒤늦게 들어온 사람이 최초 시드가 아니라 최신 상태를 받게 하려는 것이다.
     * 내용은 CRDT 라 어차피 합쳐지므로 서버가 순서를 따질 필요가 없다.
     */
    public void saveDoc(String room, String yjsUpdate) {
        if (room == null || room.isBlank() || yjsUpdate == null || yjsUpdate.isBlank()) {
            return;
        }

        roomDocs.put(room, yjsUpdate);
    }

    /**
     * 파일(또는 폴더 아래 전부)의 보관 문서를 버린다. 파일을 지우거나 이름을 바꿨을 때 부른다.
     *
     * 버리지 않으면 같은 이름으로 다시 만든 파일을 여는 사람이 지워진 파일의 옛 문서를 받는다.
     */
    public void dropDocsUnder(String roomOfPath) {
        if (roomOfPath == null || roomOfPath.isBlank()) {
            return;
        }

        roomDocs.keySet().removeIf(room -> room.equals(roomOfPath) || room.startsWith(roomOfPath + "/"));
    }

    /** 시드 결과. accepted 가 false 면 이미 있던 문서를 돌려준 것이다. */
    public record SeedOutcome(boolean accepted, String yjsUpdate) {}

    // 💡 [핵심 해결] 정확하게 쿼리 파라미터(?room=...)에서 방 이름을 뽑아냅니다!
    private String getRoomName(WebSocketSession session) {
        // 핸드셰이크에서 이미 뽑아 둔 값이 있으면 그것을 쓴다.
        // 토큰 같은 다른 쿼리 파라미터가 붙어도 방 이름이 흔들리지 않는다.
        Object cached = session.getAttributes().get("COLLAB_ROOM");
        if (cached instanceof String cachedRoom && !cachedRoom.isBlank()) {
            return cachedRoom;
        }

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