package com.myide.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개인 샌드박스 방(sbx:{userId}:{workspaceId}:…)에 주인만 들어올 수 있는지 확인한다.
 *
 * 샌드박스의 약속은 "내가 고친 파일은 팀원에게 안 보인다"이다. 방 이름만 알면 들어올
 * 수 있다면 그 약속이 깨진다.
 */
class SandboxRoomAccessTest {

    private static final String MY_ROOM = "sbx:7:ws-1:shop:feature/login:src/Main.java";

    private JwtProvider jwtProvider;
    private WorkspaceAccessGuard accessGuard;
    private CollabHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtProvider = Mockito.mock(JwtProvider.class);
        accessGuard = Mockito.mock(WorkspaceAccessGuard.class);
        interceptor = new CollabHandshakeInterceptor(jwtProvider, accessGuard);

        ReflectionTestUtils.setField(interceptor, "authEnabled", true);

        Mockito.when(jwtProvider.validateAccessToken("token-7")).thenReturn(true);
        Mockito.when(jwtProvider.getUserIdFromToken("token-7")).thenReturn(7L);
        Mockito.when(jwtProvider.validateAccessToken("token-8")).thenReturn(true);
        Mockito.when(jwtProvider.getUserIdFromToken("token-8")).thenReturn(8L);

        // 둘 다 같은 워크스페이스의 멤버다. 멤버라는 것만으로는 남의 샌드박스에 못 들어간다.
        Mockito.when(accessGuard.hasAccess(Mockito.eq("ws-1"), Mockito.anyLong())).thenReturn(true);
    }

    private HttpStatus handshake(String room, String token, Map<String, Object> attributes) {
        ServerHttpRequest request = Mockito.mock(ServerHttpRequest.class);
        ServerHttpResponse response = Mockito.mock(ServerHttpResponse.class);

        String query = "room=" + room.replace("/", "%2F") + (token == null ? "" : "&token=" + token);
        Mockito.when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws/collab?" + query));

        HttpStatus[] status = {null};
        Mockito.doAnswer(call -> {
            status[0] = call.getArgument(0);
            return null;
        }).when(response).setStatusCode(Mockito.any());

        boolean accepted = interceptor.beforeHandshake(request, response, null, attributes);

        return accepted ? HttpStatus.OK : status[0];
    }

    @Test
    @DisplayName("방 이름에서 주인과 워크스페이스를 읽는다")
    void parsesSandboxRoom() {
        assertThat(CollabRoomName.isSandboxRoom(MY_ROOM)).isTrue();
        assertThat(CollabRoomName.sandboxOwnerOf(MY_ROOM)).isEqualTo(7L);
        assertThat(CollabRoomName.workspaceIdOf(MY_ROOM)).isEqualTo("ws-1");

        assertThat(CollabRoomName.sandboxOwnerOf("sbx:abc:ws-1:shop:master:A.java")).isNull();
        assertThat(CollabRoomName.sandboxOwnerOf("ws-1:shop:master:A.java")).isNull();
    }

    @Test
    @DisplayName("샌드박스 방은 팀이 공유하는 파일 방이 아니다")
    void sandboxRoomIsNotASharedFileRoom() {
        // 파일 방으로 취급되면 샌드박스에서의 편집이 "팀 파일을 고쳤다"로 장부에 적힌다.
        assertThat(CollabRoomName.isFileRoom(MY_ROOM)).isFalse();
        assertThat(CollabRoomName.parseFileRoom(MY_ROOM)).isNull();

        assertThat(CollabRoomName.parseFileRoom("ws-1:shop:feature/login:src/Main.java"))
                .isEqualTo(new CollabRoomName.FileRoom("ws-1", "shop", "feature/login", "src/Main.java"));
    }

    @Test
    @DisplayName("주인은 자기 샌드박스 방에 들어온다")
    void ownerEnters() {
        Map<String, Object> attributes = new HashMap<>();

        assertThat(handshake(MY_ROOM, "token-7", attributes)).isEqualTo(HttpStatus.OK);
        assertThat(attributes.get(CollabHandshakeInterceptor.ATTR_USER_ID)).isEqualTo(7L);
    }

    @Test
    @DisplayName("같은 워크스페이스 멤버라도 남의 샌드박스 방에는 못 들어온다")
    void teammateIsRejected() {
        assertThat(handshake(MY_ROOM, "token-8", new HashMap<>())).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("인증 스위치를 꺼도 샌드박스 방은 본인 확인을 한다")
    void authSwitchDoesNotOpenSandboxRooms() {
        ReflectionTestUtils.setField(interceptor, "authEnabled", false);

        // 스위치는 팀 동시편집이 막혔을 때 급히 푸는 용도다. 그렇다고 남의 샌드박스가 열리면 안 된다.
        assertThat(handshake(MY_ROOM, null, new HashMap<>())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handshake(MY_ROOM, "token-8", new HashMap<>())).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handshake(MY_ROOM, "token-7", new HashMap<>())).isEqualTo(HttpStatus.OK);

        // 팀 공유 방은 스위치대로 열린다(기존 동작).
        assertThat(handshake("ws-1:shop:master:A.java", null, new HashMap<>())).isEqualTo(HttpStatus.OK);
    }
}
