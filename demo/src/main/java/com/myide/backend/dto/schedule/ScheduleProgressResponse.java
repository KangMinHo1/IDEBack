package com.myide.backend.dto.schedule;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ScheduleProgressResponse {
    private String workspaceId;
    private String workspaceName;
    private String type;
    private long totalCount;
    private long doneCount;
    private int progress;
}