package com.myide.backend.controller;

import com.myide.backend.handler.CollaborationWebSocketHandler;
import com.myide.backend.security.CollabRoomName;
import com.myide.backend.security.WorkspaceAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 동시 편집 방의 문서를 주고받는다.
 *
 * 협업 서버는 문서를 보관하지 않는 중계기라 방이 비면 문서도 사라진다.
 * 그래서 파일을 열 때마다 디스크 내용으로 문서를 새로 만들어야 하는데,
 * 여러 명이 동시에 열면 누가 만들지가 문제가 된다.
 *
 * 예전에는 서버가 "허락"만 주었다. 그러면 허락받은 사람이 내용을 넣기 전에
 * 나가 버렸을 때 나머지가 빈 문서에 갇혔다. 지금은 <b>내용 자체를 주고받는다.</b>
 * 처음 도착한 것만 채택하고 진 쪽에는 채택된 것을 돌려주므로, 아무도 빈
 * 문서에 갇히지 않고 같은 내용이 두 번 들어가지도 않는다.
 *
 * 클라이언트가 지켜야 할 순서가 있다.
 *   1) GET  /doc        저장본을 받는다
 *   2) POST /doc/seed   없으면 디스크 내용으로 만들어 보내고, 돌아온 것을 쓴다
 *   3) 그 다음에야 WebSocket 에 접속한다
 *
 * 3번을 앞당기면 접속이 먼저 이루어져 다시 경쟁이 생긴다. 설계 문서 쪽
 * DesignSnapshotController 가 같은 순서를 요구한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/collab/rooms")
public class CollabRoomController {

    private final CollaborationWebSocketHandler collaborationHandler;
    private final WorkspaceAccessGuard accessGuard;

    public record RoomDocResponse(boolean needsSeed, String yjsUpdate) {}

    public record RoomDocWriteRequest(String room, String yjsUpdate) {}

    public record RoomSeedResponse(boolean accepted, String yjsUpdate) {}

    @GetMapping("/doc")
    public RoomDocResponse getDoc(
            @AuthenticationPrincipal Long userId,
            @RequestParam String room
    ) {
        requireRoomAccess(room, userId);

        String stored = collaborationHandler.getDoc(room);

        return new RoomDocResponse(stored == null, stored);
    }

    @PostMapping("/doc/seed")
    public ResponseEntity<RoomSeedResponse> seedDoc(
            @AuthenticationPrincipal Long userId,
            @RequestBody RoomDocWriteRequest request
    ) {
        String room = request == null ? null : request.room();
        requireRoomAccess(room, userId);

        String update = request.yjsUpdate();

        if (update == null || update.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "문서 내용이 없습니다.");
        }

        CollaborationWebSocketHandler.SeedOutcome outcome =
                collaborationHandler.seedDoc(room, update);

        RoomSeedResponse body = new RoomSeedResponse(outcome.accepted(), outcome.yjsUpdate());

        // 409 는 오류가 아니라 정상적인 경쟁 결과다. 진 쪽은 자기가 만든 것을
        // 버리고 본문으로 받은 것을 써야 한다.
        return outcome.accepted()
                ? ResponseEntity.status(HttpStatus.CREATED).body(body)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @PutMapping("/doc/snapshot")
    public RoomDocResponse saveDoc(
            @AuthenticationPrincipal Long userId,
            @RequestBody RoomDocWriteRequest request
    ) {
        String room = request == null ? null : request.room();
        requireRoomAccess(room, userId);

        collaborationHandler.saveDoc(room, request.yjsUpdate());

        return new RoomDocResponse(false, null);
    }

    /**
     * 방 이름에 워크스페이스가 들어 있다는 규약 덕분에 방 이름만으로 권한을
     * 확인할 수 있다. 접속 검사와 같은 해석을 쓴다.
     */
    private void requireRoomAccess(String room, Long userId) {
        if (room == null || room.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "방 이름이 없습니다.");
        }

        String workspaceId = CollabRoomName.workspaceIdOf(room);

        if (workspaceId == null || workspaceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "방 이름에서 워크스페이스를 찾지 못했습니다.");
        }

        accessGuard.requireAccess(workspaceId, userId);

        // 개인 샌드박스 방의 문서는 주인만 읽고 쓴다. 접속 검사와 같은 규칙이다.
        if (CollabRoomName.isSandboxRoom(room) && !userId.equals(CollabRoomName.sandboxOwnerOf(room))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 사람의 샌드박스입니다.");
        }
    }
}
