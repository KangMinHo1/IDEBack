package com.myide.backend.dto.devlog;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class WorkspaceDetailResponse {
    private String uuid;
    private String name;
    private String mode;
    private String teamName;
    private List<ProjectDevlogGroupResponse> projects;
}