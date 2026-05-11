package com.myide.backend.dto.schedule;

import com.myide.backend.domain.schedule.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ScheduleCreateRequest(
        @NotBlank(message = "일정 제목은 필수입니다.")
        String title,

        String description,

        @NotNull(message = "시작일은 필수입니다.")
        LocalDate startDate,

        @NotNull(message = "종료일은 필수입니다.")
        LocalDate endDate,

        ScheduleStatus status
) {
}