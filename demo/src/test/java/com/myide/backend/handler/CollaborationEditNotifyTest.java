package com.myide.backend.handler;

import com.myide.backend.security.CollabHandshakeInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 동시 편집 중계가 "누가 어느 파일을 고쳤는지"를 제대로 알리는지 확인한다.
 *
 * 중계가 본업이므로, 알리는 쪽에서 무슨 일이 나도 팀원에게 가는 메시지는 그대로
 * 나가야 한다.
 */
class CollaborationEditNotifyTest {

    private static final byte[] EDIT = {0, 2, 1, 1};
    private static final byte[] AWARENESS = {1, 2, 1, 1};

    private CollaborationWebSocketHandler handler;
    private List<String> edits;

    @BeforeEach
    void setUp() {
        handler = new CollaborationWebSocketHandler();
        edits = new ArrayList<>();

        handler.setEditListener((workspaceId, project, branch, file, userId) ->
                edits.add(workspaceId + "|" + project + "|" + branch + "|" + file + "|" + userId));
    }

    private WebSocketSession join(String id, String room, Long userId) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CollabHandshakeInterceptor.ATTR_ROOM, room);

        if (userId != null) {
            attributes.put(CollabHandshakeInterceptor.ATTR_USER_ID, userId);
        }

        WebSocketSession session = Mockito.mock(WebSocketSession.class);
        Mockito.when(session.getId()).thenReturn(id);
        Mockito.when(session.getAttributes()).thenReturn(attributes);
        Mockito.when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        return session;
    }

    @Test
    @DisplayName("코드 파일을 고치면 방 이름에서 뽑은 위치와 회원번호를 알린다")
    void reportsEditsWithAuthor() throws Exception {
        WebSocketSession author = join("s1", "ws-1:shop:feature/login:src/app/Main.java", 7L);

        handler.handleBinaryMessage(author, new BinaryMessage(EDIT));

        assertThat(edits).containsExactly("ws-1|shop|feature/login|src/app/Main.java|7");
    }

    @Test
    @DisplayName("커서 이동은 알리지 않는다")
    void ignoresAwareness() throws Exception {
        WebSocketSession author = join("s1", "ws-1:shop:master:Main.java", 7L);

        handler.handleBinaryMessage(author, new BinaryMessage(AWARENESS));

        assertThat(edits).isEmpty();
    }

    @Test
    @DisplayName("설계 문서나 전역 방의 편집은 알리지 않는다")
    void ignoresNonFileRooms() throws Exception {
        handler.handleBinaryMessage(join("s1", "design:ws-1", 7L), new BinaryMessage(EDIT));
        handler.handleBinaryMessage(join("s2", "global-workspace-room-ws-1", 7L), new BinaryMessage(EDIT));

        assertThat(edits).isEmpty();
    }

    @Test
    @DisplayName("회원번호를 모르는 접속의 편집은 알리지 않는다")
    void ignoresAnonymousSessions() throws Exception {
        // collab.auth.enabled=false 로 띄운 경우다. 누구인지 모르는 변경을 아무에게나
        // 붙이면 실행에서 엉뚱한 사람이 제외된다.
        WebSocketSession anonymous = join("s1", "ws-1:shop:master:Main.java", null);

        handler.handleBinaryMessage(anonymous, new BinaryMessage(EDIT));

        assertThat(edits).isEmpty();
    }

    @Test
    @DisplayName("알리는 쪽이 실패해도 팀원에게는 그대로 중계된다")
    void relaysEvenWhenListenerFails() throws Exception {
        handler.setEditListener((workspaceId, project, branch, file, userId) -> {
            throw new IllegalStateException("장부 고장");
        });

        String room = "ws-1:shop:master:Main.java";
        WebSocketSession author = join("s1", room, 7L);
        WebSocketSession teammate = join("s2", room, 8L);
        BinaryMessage message = new BinaryMessage(EDIT);

        assertThatCode(() -> handler.handleBinaryMessage(author, message)).doesNotThrowAnyException();

        Mockito.verify(teammate).sendMessage(message);
        Mockito.verify(author, Mockito.never()).sendMessage(Mockito.any());
    }
}
