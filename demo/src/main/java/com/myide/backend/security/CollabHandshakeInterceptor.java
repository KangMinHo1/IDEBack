package com.myide.backend.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * /ws/collab 접속 시 JWT 와 워크스페이스 멤버십을 확인한다.
 *
 * 이 엔드포인트에는 원래 인증이 전혀 없었다. 방 이름만 알면 누구나 들어와
 * 문서를 읽고 쓸 수 있었고, 이제 여기에 팀의 설계 문서 전체가 올라가므로
 * 그대로 둘 수 없다.
 *
 * 검증 방식은 STOMP 쪽 WebSocketAuthChannelInterceptor 와 같고, 멤버십은
 * WorkspaceAccessGuard 를 그대로 쓴다. 새로 만든 것은 방 이름에서
 * 워크스페이스를 뽑아내는 부분뿐이다.
 *
 * 지원하는 방 이름 세 가지:
 *   design:{workspaceId}                        설계 문서
 *   {workspaceId}:{project}:{branch}:{file}     코드 에디터 동시편집(기존)
 *   global-workspace-room-{workspaceId}         워크스페이스 전역 동기화(기존)
 *
 * 기존 두 형식을 그대로 받아들이는 것이 중요하다. 이 엔드포인트는 설계
 * 기능만 쓰는 것이 아니라서, 형식을 바꾸면 잘 돌아가던 코드 에디터
 * 동시편집이 끊긴다.
 *
 * 토큰을 쿼리스트링으로 받는 것에 대해:
 * 브라우저 WebSocket API 는 커스텀 헤더를 보낼 수 없어서 다른 선택지가 없다.
 * 대신 토큰이 기록에 남지 않도록 이 경로에서는 요청 URI 나 쿼리를 로그에
 * 남기지 않으며, 톰캣 액세스 로그도 켜지 않는다(기본값이 꺼짐). 액세스 토큰
 * 수명이 15분으로 짧은 것도 노출 영향을 줄여 준다.
 * 더 단단히 하려면 30초짜리 일회용 접속 티켓을 따로 발급하는 방식이 있는데,
 * WebSocket 생성자가 동기라서 재접속 때마다 티켓을 미리 받아 두는 구조가
 * 필요하다. 지금은 인증이 아예 없던 상태를 메우는 것이 우선이라 뒤로 미룬다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollabHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "AUTH_USER_ID";
    public static final String ATTR_WORKSPACE_ID = "WORKSPACE_ID";
    public static final String ATTR_ROOM = "COLLAB_ROOM";

    private final JwtProvider jwtProvider;
    private final WorkspaceAccessGuard accessGuard;

    /**
     * 문제가 생겼을 때 즉시 끌 수 있는 스위치.
     * 이 인증은 설계뿐 아니라 코드 에디터 동시편집에도 영향을 주기 때문에
     * 되돌릴 수단을 남겨 둔다. 기본값은 켬이다.
     */
    @Value("${collab.auth.enabled:true}")
    private boolean authEnabled;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        Map<String, String> query = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .toSingleValueMap();

        String room = decode(query.get("room"));
        String workspaceId = extractWorkspaceId(room);

        attributes.put(ATTR_ROOM, room == null ? "default-room" : room);
        attributes.put(ATTR_WORKSPACE_ID, workspaceId);

        // 개인 샌드박스 방은 인증 스위치와 무관하게 항상 본인 확인을 한다. 스위치는 팀
        // 동시편집이 막혔을 때 급히 푸는 용도인데, 그렇다고 남의 샌드박스까지 열리면 안 된다.
        boolean sandboxRoom = CollabRoomName.isSandboxRoom(room);

        if (!authEnabled && !sandboxRoom) {
            log.warn("⚠️ [Collab] 인증이 꺼져 있습니다. 방 = {}", room);
            return true;
        }

        String token = decode(query.get("token"));

        if (token == null || token.isBlank() || !jwtProvider.validateAccessToken(token)) {
            log.info("🚫 [Collab] 토큰이 없거나 유효하지 않습니다. 방 = {}", room);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Long userId = jwtProvider.getUserIdFromToken(token);

        if (workspaceId == null || workspaceId.isBlank()) {
            log.info("🚫 [Collab] 방 이름에서 워크스페이스를 찾지 못했습니다. 방 = {}", room);
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        if (!accessGuard.hasAccess(workspaceId, userId)) {
            log.info("🚫 [Collab] 워크스페이스 접근 권한이 없습니다. 사용자 = {}, 워크스페이스 = {}",
                    userId, workspaceId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        if (sandboxRoom && !userId.equals(CollabRoomName.sandboxOwnerOf(room))) {
            log.info("🚫 [Collab] 남의 샌드박스 방입니다. 사용자 = {}", userId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        return true;
    }

    /**
     * 인증을 끈 채로 띄웠으면 기동할 때 알린다.
     *
     * 안전 실행은 "누가 고쳤는지"를 접속할 때 확인한 회원번호로 가린다. 인증이 꺼지면
     * 회원번호 없이 접속되므로 편집이 장부에 적히지 않고, 팀원의 변경을 골라 빼지 못한다
     * (출처를 모르는 변경으로만 다뤄진다).
     */
    @PostConstruct
    void warnIfAuthDisabled() {
        if (!authEnabled) {
            log.warn("⚠️ [Collab] collab.auth.enabled=false — 안전 실행의 작성자 구분이 동작하지 않습니다.");
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 별도 처리 없음
    }

    /**
     * 방 이름에서 워크스페이스 식별자를 뽑는다.
     *
     * 해석은 CollabRoomName 에 있다. 최초 내용 시드를 허락하는 쪽도 같은
     * 해석을 써야 하므로, 두 벌을 두지 않고 한곳을 부른다.
     */
    static String extractWorkspaceId(String room) {
        return CollabRoomName.workspaceIdOf(room);
    }

    private String decode(String value) {
        if (value == null) {
            return null;
        }

        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
