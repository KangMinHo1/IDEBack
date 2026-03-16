package com.myide.backend.controller;

import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.workspace.InviteMemberRequest;
import com.myide.backend.dto.workspace.WorkspaceCreateRequest;
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
}