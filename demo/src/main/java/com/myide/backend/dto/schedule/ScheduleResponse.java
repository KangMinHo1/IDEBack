package com.myide.backend.dto.schedule;

import com.myide.backend.domain.Schedule;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class ScheduleResponse {

    private Long id;
    private String type;
    private String workspaceId;
    private String workspaceName;
    private Long creatorId;
    private String creatorName;

    private String title;
    private LocalDate startDate;
    private LocalDate endDate;
    private String category;
    private String location;
    private String stage;
    private String role;
    private String status;
    private String participants;
    private String description;

    public static ScheduleResponse from(Schedule schedule) {
        return ScheduleResponse.builder()
                .id(schedule.getId())
                .type(schedule.getType().name())
                .workspaceId(schedule.getWorkspace() != null ? schedule.getWorkspace().getUuid() : null)
                .workspaceName(schedule.getWorkspace() != null ? schedule.getWorkspace().getName() : null)
                .creatorId(schedule.getCreator().getId())
                .creatorName(schedule.getCreator().getNickname())
                .title(schedule.getTitle())
                .startDate(schedule.getStartDate())
                .endDate(schedule.getEndDate())
                .category(schedule.getCategory().name())
                .location(schedule.getLocation())
                .stage(schedule.getStage().name())
                .role(schedule.getRole().name())
                .status(schedule.getStatus().name())
                .participants(schedule.getParticipants())
                .description(schedule.getDescription())
                .build();
    }
}