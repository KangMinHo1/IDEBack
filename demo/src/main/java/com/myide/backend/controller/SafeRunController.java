// 경로: src/main/java/com/myide/backend/controller/SafeRunController.java
package com.myide.backend.controller;

import com.myide.backend.security.WorkspaceAccessGuard;
import com.myide.backend.service.saferun.BranchKey;
import com.myide.backend.service.saferun.SafeRunStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 안전 실행 상태 조회.
 *
 * 상태가 바뀔 때는 /ws/workspace-events 로 알림(AUTHOR_STATUS_CHANGED, GREEN_UPDATED)이
 * 가지만, 알림은 바뀔 때만 나간다. 화면을 처음 열거나 브랜치를 바꾼 직후에는 이
 * API 로 현재 상태를 한 번 받아 와야 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/safe-run")
public class SafeRunController {

    private final SafeRunStatusService statusService;
    private final WorkspaceAccessGuard accessGuard;

    @GetMapping("/status")
    public SafeRunStatusService.StatusView status(
            @AuthenticationPrincipal Long userId,
            @RequestParam String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "master") String branchName
    ) {
        accessGuard.requireAccess(workspaceId, userId);

        return statusService.view(BranchKey.of(workspaceId, projectName, branchName));
    }
}
