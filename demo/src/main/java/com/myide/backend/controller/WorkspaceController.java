package com.myide.backend.controller;

import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.workspace.InviteMemberRequest;
import com.myide.backend.dto.workspace.WorkspaceCreateRequest;
import com.myide.backend.dto.workspace.WorkspaceInvitationResponse;
import com.myide.backend.dto.workspace.WorkspaceListResponse;
import com.myide.backend.dto.workspace.WorkspaceMemberResponse;
import com.myide.backend.service.WorkspaceService;
import com.myide.backend.service.CurrentUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final CurrentUserService currentUserService; // ✅ 토큰 기반 사용자 식별


    @GetMapping
    public ResponseEntity<List<WorkspaceListResponse>> getMyWorkspaces(@RequestParam Long userId) {
        return ResponseEntity.ok(workspaceService.getMyWorkspaces(userId));
    }

    // ✅ 마이페이지 전용: 토큰에서 userId 꺼내서 조회
    @GetMapping("/me")
    public ResponseEntity<List<WorkspaceListResponse>> getMyWorkspacesByToken() {
        Long userId = currentUserService.getCurrentUserId();
        return ResponseEntity.ok(workspaceService.getMyWorkspaces(userId));
    }

    @PostMapping
    public ResponseEntity<Workspace> createWorkspace(@RequestBody @Valid WorkspaceCreateRequest request) {
        Workspace createdWorkspace = workspaceService.createWorkspace(request);
        return ResponseEntity.ok(createdWorkspace);
    }

    @PostMapping("/invite")
    public ResponseEntity<String> inviteMember(@RequestBody @Valid InviteMemberRequest request) {
        try {
            workspaceService.inviteMember(request);
            return ResponseEntity.ok("팀원에게 초대가 발송되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{workspaceId}/accept")
    public ResponseEntity<String> acceptInvitation(@PathVariable String workspaceId, @RequestParam Long userId) {
        try {
            workspaceService.acceptInvitation(workspaceId, userId);
            return ResponseEntity.ok("워크스페이스 초대를 수락했습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{workspaceId}/reject")
    public ResponseEntity<String> rejectInvitation(@PathVariable String workspaceId, @RequestParam Long userId) {
        try {
            workspaceService.rejectInvitation(workspaceId, userId);
            return ResponseEntity.ok("워크스페이스 초대를 거절했습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/invitations")
    public ResponseEntity<List<WorkspaceInvitationResponse>> getPendingInvitations(@RequestParam Long userId) {
        return ResponseEntity.ok(workspaceService.getPendingInvitations(userId));
    }

    @GetMapping("/{workspaceId}/members")
    public ResponseEntity<List<WorkspaceMemberResponse>> getWorkspaceMembers(@PathVariable String workspaceId) {
        return ResponseEntity.ok(workspaceService.getWorkspaceMembers(workspaceId));
    }
}