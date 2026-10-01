// 경로: src/main/java/com/myide/backend/service/saferun/YjsMessageKind.java
package com.myide.backend.service.saferun;

import java.nio.ByteBuffer;

/**
 * 동시 편집 메시지가 "문서를 고친 것"인지 겉만 보고 가린다.
 *
 * 서버는 Yjs 내용을 해석하지 않는다. 하지만 y-websocket 은 모든 메시지 앞에 종류를
 * 적어 보내므로, 내용을 열지 않고도 편집인지는 알 수 있다.
 *
 * <pre>
 *   첫 바이트   0 = 문서 동기화, 1 = awareness(커서·접속자)
 *   둘째 바이트 0 = 동기화 1단계(내 상태 알림), 1 = 2단계(빠진 것 보내기), 2 = 편집
 * </pre>
 *
 * 커서만 움직여도 awareness 가 계속 오고, 방에 들어올 때는 1·2단계가 오간다. 이것들을
 * 편집으로 세면 파일을 열기만 한 사람이 작성자로 적힌다. 그래서 "0, 2" 만 편집으로 본다.
 */
public final class YjsMessageKind {

    private static final int MESSAGE_SYNC = 0;
    private static final int SYNC_UPDATE = 2;

    private YjsMessageKind() {
    }

    public static boolean isDocumentEdit(ByteBuffer payload) {
        if (payload == null || payload.remaining() < 2) {
            return false;
        }

        // 읽는 위치를 옮기지 않는다. 같은 버퍼가 이어서 팀원에게 중계된다.
        int start = payload.position();

        return payload.get(start) == MESSAGE_SYNC
                && payload.get(start + 1) == SYNC_UPDATE;
    }
}
