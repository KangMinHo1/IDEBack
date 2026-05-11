package com.myide.backend.dto.devlog;

import com.myide.backend.domain.devlog.Devlog;
import com.myide.backend.domain.schedule.Schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DevlogResponse(
        String id,
        String workspaceId,
        String projectName,
        String title,
        String content,
        LocalDate workedDate,
        String type,
        String scheduleId,
        String scheduleTitle,
        String scheduleStatus,
        String category,
        List<String> tags,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static DevlogResponse from(Devlog devlog) {
        Schedule schedule = devlog.getSchedule();

        return new DevlogResponse(
                devlog.getUuid(),
                devlog.getWorkspace().getUuid(),
                devlog.getWorkspace().getName(),
                devlog.getTitle(),
                devlog.getContent(),
                devlog.getWorkedDate(),
                schedule == null ? "general" : "linked",
                schedule == null ? null : schedule.getUuid(),
                schedule == null ? null : schedule.getTitle(),
                schedule == null ? null : schedule.getStatus().getValue(),
                devlog.getCategory(),
                devlog.getTags(),
                devlog.getCreatedAt(),
                devlog.getUpdatedAt()
        );
    }
}