package com.myide.backend.controller;

import com.myide.backend.dto.devlog.DevlogCreateRequest;
import com.myide.backend.dto.devlog.DevlogResponse;
import com.myide.backend.dto.devlog.DevlogUpdateRequest;
import com.myide.backend.service.DevlogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class DevlogController {

    private final DevlogService devlogService;

    // author: 선택. 없음/all = 전체, me = 내가 쓴 일지, 숫자 = 그 회원이 쓴 일지
    @GetMapping("/workspaces/{workspaceId}/devlogs")
    public List<DevlogResponse> getDevlogs(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String author
    ) {
        return devlogService.getDevlogs(workspaceId, userId, author);
    }

    @PostMapping("/workspaces/{workspaceId}/devlogs")
    public DevlogResponse createDevlog(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DevlogCreateRequest request
    ) {
        return devlogService.createDevlog(workspaceId, userId, request);
    }

    @GetMapping("/devlogs/{devlogId}")
    public DevlogResponse getDevlog(
            @PathVariable String devlogId,
            @AuthenticationPrincipal Long userId
    ) {
        return devlogService.getDevlog(devlogId, userId);
    }

    @PatchMapping("/devlogs/{devlogId}")
    public DevlogResponse updateDevlog(
            @PathVariable String devlogId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DevlogUpdateRequest request
    ) {
        return devlogService.updateDevlog(devlogId, userId, request);
    }

    @DeleteMapping("/devlogs/{devlogId}")
    public void deleteDevlog(
            @PathVariable String devlogId,
            @AuthenticationPrincipal Long userId
    ) {
        devlogService.deleteDevlog(devlogId, userId);
    }

    @GetMapping("/schedules/{scheduleId}/devlogs")
    public List<DevlogResponse> getDevlogsBySchedule(
            @PathVariable String scheduleId,
            @AuthenticationPrincipal Long userId
    ) {
        return devlogService.getDevlogsBySchedule(scheduleId, userId);
    }
}