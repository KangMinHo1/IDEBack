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
    // ✅ 현재 사용자가 이 워크스페이스에서 owner인지 member인지
    private String role; // "owner" | "member"
    private String updatedAt;
    private String description;
    private String teamName; // 지금은 없으니 null 처리
    private List<ProjectSummaryResponse> projects;

    // ✅ userId를 받아서 현재 사용자 기준 role 계산
    public static WorkspaceListResponse from(Workspace workspace, Long userId) {
        String role = workspace.getOwner().getId().equals(userId) ? "owner" : "member";

        return WorkspaceListResponse.builder()
                .id(workspace.getUuid())
                .name(workspace.getName())
                .mode(workspace.getType() == WorkspaceType.TEAM ? "team" : "personal")
                .role(role)
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