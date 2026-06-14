package com.myide.backend.dto.mypage;

import java.time.LocalDate;

public record ActivityHeatmapDayResponse(
        LocalDate date,
        int count,
        int level
) {
}