package com.myide.backend.controller;

import com.myide.backend.dto.project.ProjectListResponse;
import com.myide.backend.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /**
     * Workspace 내부 작업 폴더 목록 조회
     */
    @GetMapping("/workspace/{workspaceId}")
    public List<ProjectListResponse> getProjectsByWorkspace(
            @PathVariable String workspaceId
    ) {
        return projectService.getProjectsByWorkspace(workspaceId);
    }

    /**
     * 작업 폴더 삭제
     *
     * - Workspace OWNER만 삭제 가능
     * - Project DB 데이터 삭제
     * - DB COMMIT 성공 후 실제 작업 폴더 삭제
     */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(
            @PathVariable Long projectId,
            @AuthenticationPrincipal Long userId
    ) {
        if (userId == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }

        projectService.deleteProject(projectId, userId);
    }
}