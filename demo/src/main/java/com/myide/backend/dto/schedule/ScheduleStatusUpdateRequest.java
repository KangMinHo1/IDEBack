package com.myide.backend.dto.schedule;

import com.myide.backend.domain.schedule.ScheduleStatus;
import jakarta.validation.constraints.NotNull;

public record ScheduleStatusUpdateRequest(
        @NotNull(message = "변경할 상태는 필수입니다.")
        ScheduleStatus status
) {
}