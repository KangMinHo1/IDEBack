package com.myide.backend.dto.notification;

import com.myide.backend.domain.notification.Notification;
import com.myide.backend.domain.notification.NotificationType;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String body,
        String targetUrl,
        boolean read,
        String workspaceId,
        String workspaceName,
        LocalDateTime createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getTargetUrl(),
                notification.isRead(),
                notification.getWorkspace() != null ? notification.getWorkspace().getUuid() : null,
                notification.getWorkspace() != null ? notification.getWorkspace().getName() : null,
                notification.getCreatedAt()
        );
    }
}