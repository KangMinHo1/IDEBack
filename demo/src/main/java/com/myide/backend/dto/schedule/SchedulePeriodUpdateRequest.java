package com.myide.backend.dto.schedule;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record SchedulePeriodUpdateRequest(
        @NotNull(message = "시작일은 필수입니다.")
        LocalDate startDate,

        @NotNull(message = "종료일은 필수입니다.")
        LocalDate endDate
) {
}