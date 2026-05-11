package com.myide.backend.dto.project;

import com.myide.backend.domain.LanguageType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ProjectListResponse {

    private Long id;
    private String name;
    private String description;
    private LanguageType language;
    private String gitUrl;
    private LocalDateTime updatedAt;

    private String workspaceId;
    private String workspaceName;
}