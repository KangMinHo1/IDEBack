package com.myide.backend.dto.mypage;

import java.util.List;

public record ActivityHeatmapResponse(
        List<ActivityHeatmapDayResponse> days,
        int totalActivityCount,
        int activeDays,
        int devlogCount,
        int scheduleDoneCount,
        int commitCount
) {
}