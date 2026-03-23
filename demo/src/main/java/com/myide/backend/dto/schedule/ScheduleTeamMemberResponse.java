package com.myide.backend.dto.schedule;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ScheduleTeamMemberResponse {
    private Long userId;
    private String name;
}