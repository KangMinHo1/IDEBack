package com.myide.backend.dto.schedule;

import com.myide.backend.domain.schedule.Schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ScheduleResponse(
        String id,
        String workspaceId,
        String projectName,
        String title,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        boolean hasDevlog,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ScheduleResponse from(Schedule schedule, boolean hasDevlog) {
        return new ScheduleResponse(
                schedule.getUuid(),
                schedule.getWorkspace().getUuid(),
                schedule.getWorkspace().getName(),
                schedule.getTitle(),
                schedule.getDescription(),
                schedule.getStartDate(),
                schedule.getEndDate(),
                schedule.getStatus().getValue(),
                hasDevlog,
                schedule.getCreatedAt(),
                schedule.getUpdatedAt()
        );
    }
}