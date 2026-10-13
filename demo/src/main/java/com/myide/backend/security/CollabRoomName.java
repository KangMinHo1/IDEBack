package com.myide.backend.security;

/**
 * 동시 편집 방 이름 규약.
 *
 * 모든 방 이름에는 워크스페이스 식별자가 들어 있다. 그래야 서버가 방 이름만
 * 보고 권한을 검사할 수 있고, 나중에 협업 서버를 다른 것으로 옮기더라도
 * 프론트를 고치지 않아도 된다.
 *
 * 지원하는 세 가지:
 * <pre>
 *   design:{workspaceId}                        설계 문서
 *   {workspaceId}:{project}:{branch}:{file}     코드 에디터 동시편집
 *   global-workspace-room-{workspaceId}         워크스페이스 전역 동기화
 * </pre>
 *
 * 기존 두 형식을 그대로 받아들이는 것이 중요하다. 이 엔드포인트는 설계
 * 기능만 쓰는 것이 아니라서, 형식을 바꾸면 잘 돌아가던 코드 에디터
 * 동시편집이 끊긴다.
 *
 * 접속 검사(CollabHandshakeInterceptor)와 최초 내용 시드 허락
 * (CollabRoomController)이 같은 해석을 써야 하므로 여기 한곳에 둔다.
 */
public final class CollabRoomName {

    private static final String DESIGN_PREFIX = "design:";
    private static final String GLOBAL_PREFIX = "global-workspace-room-";

    /**
     * 개인 샌드박스 문서 방: sbx:{userId}:{workspaceId}:{project}:{branch}:{file}
     *
     * 샌드박스에서 고치는 파일은 팀과 공유하는 방이 아니라 이 방에서 편집한다. 그래서
     * 팀원에게 보이지 않는다. 방 이름에 주인의 회원번호가 들어 있고, 주인만 들어올 수 있다.
     */
    private static final String SANDBOX_PREFIX = "sbx:";

    private CollabRoomName() {
    }

    /** 코드 에디터가 팀과 공유하는 방의 이름. 프론트(CodeEditor)가 만드는 것과 같아야 한다. */
    public static String fileRoom(String workspaceId, String projectName, String branchName, String filePath) {
        return workspaceId + ":" + projectName + ":" + branchName + ":" + filePath;
    }

    /** 개인 샌드박스 방인가. */
    public static boolean isSandboxRoom(String room) {
        return room != null && room.startsWith(SANDBOX_PREFIX);
    }

    /** 샌드박스 방의 주인(회원번호). 샌드박스 방이 아니거나 형식이 틀리면 null. */
    public static Long sandboxOwnerOf(String room) {
        if (!isSandboxRoom(room)) {
            return null;
        }

        String[] parts = room.split(":", 3);

        try {
            return parts.length == 3 ? Long.valueOf(parts[1]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 방 이름에서 워크스페이스 식별자를 뽑는다. 알 수 없으면 null. */
    public static String workspaceIdOf(String room) {
        if (room == null || room.isBlank()) {
            return null;
        }

        if (isSandboxRoom(room)) {
            // sbx:{userId}:{workspaceId}:… — 셋째 칸이 워크스페이스다.
            String[] parts = room.split(":", 4);
            return parts.length == 4 && !parts[2].isBlank() ? parts[2] : null;
        }

        if (room.startsWith(DESIGN_PREFIX)) {
            return room.substring(DESIGN_PREFIX.length());
        }

        if (room.startsWith(GLOBAL_PREFIX)) {
            return room.substring(GLOBAL_PREFIX.length());
        }

        // 코드 에디터는 workspaceId:project:branch:file 형태로 방을 만든다.
        int separator = room.indexOf(':');
        return separator > 0 ? room.substring(0, separator) : room;
    }

    /** 코드 에디터 방 이름을 쪼갠 것. */
    public record FileRoom(String workspaceId, String projectName, String branchName, String filePath) {}

    /**
     * 코드 에디터 방 이름({workspaceId}:{project}:{branch}:{file})을 쪼갠다.
     * 그 형식이 아니면 null.
     *
     * 브랜치 이름에는 ':' 가 올 수 없다(프론트와 GitController 가 막는다). 그래서 앞의
     * 세 칸만 ':' 로 자르고 나머지는 전부 파일 경로로 본다.
     */
    public static FileRoom parseFileRoom(String room) {
        if (!isFileRoom(room)) {
            return null;
        }

        String[] parts = room.split(":", 4);

        if (parts.length < 4 || parts[1].isBlank() || parts[2].isBlank() || parts[3].isBlank()) {
            return null;
        }

        return new FileRoom(parts[0], parts[1], parts[2], parts[3]);
    }

    /**
     * 파일 하나를 여는 방인가.
     *
     * 최초 내용을 디스크에서 읽어 넣어야 하는 것은 코드 에디터 방뿐이다.
     * 설계 문서는 서버에 저장본이 따로 있고, 전역 동기화 방은 내용이 없다.
     */
    public static boolean isFileRoom(String room) {
        if (room == null || room.isBlank()) {
            return false;
        }

        // 샌드박스 방은 팀이 공유하는 파일 방이 아니다. 여기서 걸러 두어야 샌드박스에서의
        // 편집이 편집 장부에 "팀 파일을 고쳤다"로 적히지 않는다.
        return !room.startsWith(DESIGN_PREFIX)
                && !room.startsWith(GLOBAL_PREFIX)
                && !room.startsWith(SANDBOX_PREFIX)
                && room.indexOf(':') > 0;
    }
}
