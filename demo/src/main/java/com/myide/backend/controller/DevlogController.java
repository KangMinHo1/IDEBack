package com.myide.backend.controller;

import com.myide.backend.dto.devlog.DevlogCreateRequest;
import com.myide.backend.dto.devlog.DevlogDetailResponse;
import com.myide.backend.dto.devlog.DevlogUpdateRequest;
import com.myide.backend.dto.devlog.WorkspaceDetailResponse;
import com.myide.backend.dto.devlog.WorkspaceListItemResponse;
import com.myide.backend.service.DevlogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devlogs")
@RequiredArgsConstructor
public class DevlogController {

    private final DevlogService devlogService;

    @GetMapping("/workspaces")
    public List<WorkspaceListItemResponse> getMyWorkspaces(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "latest") String sort
    ) {
        return devlogService.getMyWorkspaces(q, sort);
    }

    @GetMapping("/workspaces/{workspaceId}")
    public WorkspaceDetailResponse getWorkspaceDetail(
            @PathVariable String workspaceId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "latest") String sort
    ) {
        return devlogService.getWorkspaceDetail(workspaceId, q, sort);
    }

    @GetMapping("/workspaces/{workspaceId}/projects/{projectId}/posts/{devlogId}")
    public DevlogDetailResponse getDevlogDetail(
            @PathVariable String workspaceId,
            @PathVariable Long projectId,
            @PathVariable Long devlogId
    ) {
        return devlogService.getDevlogDetail(workspaceId, projectId, devlogId);
    }

    @PostMapping
    public ResponseEntity<DevlogDetailResponse> create(@Valid @RequestBody DevlogCreateRequest request) {
        return ResponseEntity.ok(devlogService.create(request));
    }

    @PutMapping("/{devlogId}")
    public ResponseEntity<DevlogDetailResponse> update(
            @PathVariable Long devlogId,
            @Valid @RequestBody DevlogUpdateRequest request
    ) {
        return ResponseEntity.ok(devlogService.update(devlogId, request));
    }

    @DeleteMapping("/{devlogId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long devlogId,
            @RequestParam String workspaceId,
            @RequestParam Long projectId
    ) {
        devlogService.delete(workspaceId, projectId, devlogId);
        return ResponseEntity.ok().build();
    }
}