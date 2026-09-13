package com.myide.backend.dto.mypage;

import java.time.LocalDateTime;

public record RecentActivityResponse(
        String id,
        String type,
        String title,
        String description,
        String workspaceId,
        String workspaceName,
        LocalDateTime occurredAt
) {
}