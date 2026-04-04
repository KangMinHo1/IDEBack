package com.myide.backend.dto.workspace;

import com.myide.backend.domain.Project;
import lombok.Builder;
import lombok.Getter;

import java.time.format.DateTimeFormatter;

@Getter
@Builder
public class ProjectSummaryResponse {

    private String id;
    private String name;
    private String language;
    private String updatedAt;

    public static ProjectSummaryResponse from(Project project) {
        return ProjectSummaryResponse.builder()
                .id(String.valueOf(project.getId()))
                .name(project.getName())
                .language(project.getLanguage().name())
                .updatedAt(
                        project.getUpdatedAt() != null
                                ? project.getUpdatedAt().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
                                : ""
                )
                .build();
    }
}