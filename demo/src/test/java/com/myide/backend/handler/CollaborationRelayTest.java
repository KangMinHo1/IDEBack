package com.myide.backend.handler;

import com.myide.backend.security.CollabHandshakeInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

/**
 * 동시 편집 중계가 메시지를 누구에게 보내는지 확인한다.
 *
 * awareness 를 보낸 사람에게 돌려주지 않으면, 방에 혼자 있는 클라이언트가 30초마다
 * 연결을 끊고 다시 붙는다. 그때마다 방 문서가 지워져 같은 파일을 연 다른 사람이
 * 원본을 새로 만들고, 두 원본이 합쳐지면서 내용이 두 번 들어갔다.
 */
class CollaborationRelayTest {

    private static final String ROOM = "ws-1:shop:master:Main.java";
    private static final byte[] EDIT = {0, 2, 1, 1};
    private static final byte[] SYNC_STEP1 = {0, 0, 1, 1};
    private static final byte[] AWARENESS = {1, 2, 1, 1};

    private CollaborationWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CollaborationWebSocketHandler();
    }

    private WebSocketSession join(String id) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CollabHandshakeInterceptor.ATTR_ROOM, ROOM);

        WebSocketSession session = Mockito.mock(WebSocketSession.class);
        Mockito.when(session.getId()).thenReturn(id);
        Mockito.when(session.getAttributes()).thenReturn(attributes);
        Mockito.when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        return session;
    }

    @Test
    @DisplayName("혼자 있어도 자기 awareness 를 돌려받는다")
    void echoesAwarenessToLoneSender() throws Exception {
        WebSocketSession alone = join("s1");
        BinaryMessage message = new BinaryMessage(AWARENESS);

        handler.handleBinaryMessage(alone, message);

        Mockito.verify(alone).sendMessage(message);
    }

    @Test
    @DisplayName("awareness 는 보낸 사람과 팀원 모두에게 간다")
    void relaysAwarenessToEveryone() throws Exception {
        WebSocketSession sender = join("s1");
        WebSocketSession teammate = join("s2");
        BinaryMessage message = new BinaryMessage(AWARENESS);

        handler.handleBinaryMessage(sender, message);

        Mockito.verify(sender).sendMessage(message);
        Mockito.verify(teammate).sendMessage(message);
    }

    @Test
    @DisplayName("문서 동기화 메시지는 보낸 사람에게 돌려주지 않는다")
    void doesNotEchoSyncMessages() throws Exception {
        WebSocketSession sender = join("s1");
        WebSocketSession teammate = join("s2");

        for (byte[] bytes : new byte[][]{EDIT, SYNC_STEP1}) {
            BinaryMessage message = new BinaryMessage(bytes);

            handler.handleBinaryMessage(sender, message);

            Mockito.verify(teammate).sendMessage(message);
        }

        Mockito.verify(sender, Mockito.never()).sendMessage(Mockito.any());
    }
}
