package com.myide.backend.dto.schedule;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ScheduleSummaryResponse {
    private long monthCount;
    private long todayCount;
    private long weekCount;
}