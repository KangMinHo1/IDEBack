package com.myide.backend.controller;

import com.myide.backend.dto.devlog.*;
import com.myide.backend.service.DevlogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devlogs")
@RequiredArgsConstructor
public class DevlogController {

    private final DevlogService devlogService;

    // 개발일지 헤더 -> 내가 참여 중인 워크스페이스 목록
    @GetMapping("/workspaces")
    public List<WorkspaceListItemResponse> getMyWorkspaces(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "latest") String sort
    ) {
        return devlogService.getMyWorkspaces(q, sort);
    }

    // 워크스페이스 상세 -> 프로젝트 + 프로젝트별 개발일지 중첩
    @GetMapping("/workspaces/{workspaceId}")
    public WorkspaceDetailResponse getWorkspaceDetail(
            @PathVariable String workspaceId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "latest") String sort
    ) {
        return devlogService.getWorkspaceDetail(workspaceId, q, sort);
    }

    // 개발일지 상세
    @GetMapping("/workspaces/{workspaceId}/projects/{projectId}/posts/{devlogId}")
    public DevlogDetailResponse getDevlogDetail(
            @PathVariable String workspaceId,
            @PathVariable Long projectId,
            @PathVariable Long devlogId
    ) {
        return devlogService.getDevlogDetail(workspaceId, projectId, devlogId);
    }

    // 새 일지 작성
    @PostMapping
    public DevlogDetailResponse create(@Valid @RequestBody DevlogCreateRequest request) {
        return devlogService.create(request);
    }

    // 수정
    @PutMapping("/{devlogId}")
    public DevlogDetailResponse update(
            @PathVariable Long devlogId,
            @Valid @RequestBody DevlogUpdateRequest request
    ) {
        return devlogService.update(devlogId, request);
    }

    // 삭제
    @DeleteMapping("/{devlogId}")
    public void delete(
            @PathVariable Long devlogId,
            @RequestParam String workspaceId,
            @RequestParam Long projectId
    ) {
        devlogService.delete(workspaceId, projectId, devlogId);
    }
}