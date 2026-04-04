package com.myide.backend.dto.workspace;

import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceType;
import lombok.Builder;
import lombok.Getter;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
public class WorkspaceListResponse {

    private String id;
    private String name;
    private String mode; // "team" | "personal"
    private String updatedAt;
    private String description;
    private String teamName; // 지금은 없으니 null 처리
    private List<ProjectSummaryResponse> projects;

    public static WorkspaceListResponse from(Workspace workspace) {
        return WorkspaceListResponse.builder()
                .id(workspace.getUuid())
                .name(workspace.getName())
                .mode(workspace.getType() == WorkspaceType.TEAM ? "team" : "personal")
                .updatedAt(
                        workspace.getUpdatedAt() != null
                                ? workspace.getUpdatedAt().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
                                : ""
                )
                .description(workspace.getDescription())
                .teamName(null)
                .projects(
                        workspace.getProjects().stream()
                                .sorted(Comparator.comparing(
                                        p -> p.getUpdatedAt() == null ? java.time.LocalDateTime.MIN : p.getUpdatedAt(),
                                        Comparator.reverseOrder()
                                ))
                                .map(ProjectSummaryResponse::from)
                                .collect(Collectors.toList())
                )
                .build();
    }
}