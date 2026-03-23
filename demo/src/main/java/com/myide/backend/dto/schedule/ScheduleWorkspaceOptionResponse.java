package com.myide.backend.dto.schedule;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ScheduleWorkspaceOptionResponse {
    private String workspaceId;
    private String workspaceName;
}