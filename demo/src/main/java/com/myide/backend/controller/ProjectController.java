package com.myide.backend.controller;

import com.myide.backend.dto.project.ProjectListResponse;
import com.myide.backend.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping("/workspace/{workspaceId}")
    public List<ProjectListResponse> getProjectsByWorkspace(
            @PathVariable String workspaceId
    ) {
        return projectService.getProjectsByWorkspace(workspaceId);
    }
}