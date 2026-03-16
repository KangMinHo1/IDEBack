package com.myide.backend.controller;

import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.workspace.InviteMemberRequest;
import com.myide.backend.dto.workspace.WorkspaceCreateRequest;
import com.myide.backend.dto.workspace.WorkspaceInvitationResponse;
import com.myide.backend.dto.workspace.WorkspaceMemberResponse;
import com.myide.backend.service.WorkspaceService;
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

    @GetMapping
    // 💡 [수정] 프론트엔드에서 넘어오는 쿼리 파라미터도 Long 타입으로 매핑합니다.
    public ResponseEntity<List<Workspace>> getMyWorkspaces(@RequestParam Long userId) {
        return ResponseEntity.ok(workspaceService.getMyWorkspaces(userId));
    }

    // 워크스페이스 생성
    @PostMapping
    public ResponseEntity<Workspace> createWorkspace(@RequestBody @Valid WorkspaceCreateRequest request) {
        Workspace createdWorkspace = workspaceService.createWorkspace(request);
        return ResponseEntity.ok(createdWorkspace);
    }

    // 팀원 초대
    @PostMapping("/invite")
    public ResponseEntity<String> inviteMember(@RequestBody @Valid InviteMemberRequest request) {
        try {
            workspaceService.inviteMember(request);
            return ResponseEntity.ok("팀원에게 초대가 발송되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 💡  초대 수락 API
    @PostMapping("/{workspaceId}/accept")
    public ResponseEntity<String> acceptInvitation(@PathVariable String workspaceId, @RequestParam Long userId) {
        try {
            workspaceService.acceptInvitation(workspaceId, userId);
            return ResponseEntity.ok("워크스페이스 초대를 수락했습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 💡  초대 거절 API
    @PostMapping("/{workspaceId}/reject")
    public ResponseEntity<String> rejectInvitation(@PathVariable String workspaceId, @RequestParam Long userId) {
        try {
            workspaceService.rejectInvitation(workspaceId, userId);
            return ResponseEntity.ok("워크스페이스 초대를 거절했습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 💡 [신규 추가] 대기 중인 초대 목록 조회 API
    @GetMapping("/invitations")
    public ResponseEntity<List<WorkspaceInvitationResponse>> getPendingInvitations(@RequestParam Long userId) {
        // WorkspaceService 쪽에 getPendingInvitations(userId) 메서드를 구현해주셔야 합니다!
        // (유저의 초대장 DB 테이블이나 연관관계를 조회해서 리스트로 반환하는 로직)
        return ResponseEntity.ok(workspaceService.getPendingInvitations(userId));
    }


    // 💡  워크스페이스 팀원 목록 조회 API
    @GetMapping("/{workspaceId}/members")
    public ResponseEntity<List<WorkspaceMemberResponse>> getWorkspaceMembers(@PathVariable String workspaceId) {
        return ResponseEntity.ok(workspaceService.getWorkspaceMembers(workspaceId));
    }
}