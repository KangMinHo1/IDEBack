package com.myide.backend.dto.schedule;

import com.myide.backend.domain.Schedule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.time.LocalDate;

@Getter
public class ScheduleCreateRequest {

    @NotNull
    private Schedule.ScheduleType type;

    /**
     * PERSONAL / TEAM 모두 필수
     */
    @NotBlank
    private String workspaceId;

    @NotBlank
    private String title;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @NotNull
    private Schedule.ScheduleCategory category;

    private String location;

    @NotNull
    private Schedule.ScheduleStage stage;

    @NotNull
    private Schedule.ScheduleRole role;

    @NotNull
    private Schedule.ScheduleStatus status;

    /**
     * TEAM 일정에서만 사용
     */
    private String participants;

    private String description;
}