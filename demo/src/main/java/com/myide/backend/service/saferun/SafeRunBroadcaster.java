// 경로: src/main/java/com/myide/backend/service/saferun/SafeRunBroadcaster.java
package com.myide.backend.service.saferun;

import com.myide.backend.handler.WorkspaceEventWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 같은 브랜치를 보고 있는 사람들에게 안전 실행 상태를 알린다.
 *
 * 새 소켓을 만들지 않고 파일 트리 이벤트(/ws/workspace-events)의 브랜치 방을 그대로 쓴다.
 * 프론트가 이미 브랜치마다 그 방에 붙어 있어서 연결을 하나 더 늘릴 필요가 없다.
 */
@Component
@RequiredArgsConstructor
public class SafeRunBroadcaster implements AuthorStatusService.ChangeListener {

    public static final String AUTHOR_STATUS_CHANGED = "AUTHOR_STATUS_CHANGED";
    public static final String GREEN_UPDATED = "GREEN_UPDATED";

    private final SafeRunStatusService statusService;
    private final WorkspaceEventWebSocketHandler workspaceEventHandler;

    @Override
    public void onStatusChanged(BranchKey key) {
        send(AUTHOR_STATUS_CHANGED, key);
    }

    public void greenUpdated(BranchKey key) {
        send(GREEN_UPDATED, key);
    }

    private void send(String type, BranchKey key) {
        SafeRunStatusService.StatusView view = statusService.view(key);

        // 받는 쪽이 조회 API 응답과 같은 코드로 읽을 수 있게 같은 필드를 그대로 싣는다.
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("workspaceId", view.workspaceId());
        message.put("projectName", view.projectName());
        message.put("branchName", view.branchName());
        message.put("greenUpdatedAt", view.greenUpdatedAt());
        message.put("authors", view.authors());

        workspaceEventHandler.broadcastToBranch(
                key.workspaceId(), key.projectName(), key.branchName(), message);
    }
}
