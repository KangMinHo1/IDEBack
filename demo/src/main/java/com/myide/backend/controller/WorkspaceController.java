package com.myide.backend.controller;

import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.workspace.InviteMemberRequest;
import com.myide.backend.dto.workspace.WorkspaceCreateRequest;
import com.myide.backend.dto.workspace.WorkspaceDuplicateRequest;
import com.myide.backend.dto.workspace.WorkspaceInvitationResponse;
import com.myide.backend.dto.workspace.WorkspaceListResponse;
import com.myide.backend.dto.workspace.WorkspaceMemberResponse;
import com.myide.backend.dto.workspace.WorkspaceUpdateRequest;
import com.myide.backend.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;


    /*
     * =====================================================
     * 내 워크스페이스 조회
     *
     * 기존 방식
     *
     * GET /api/workspaces?userId=1
     * =====================================================
     */

    @GetMapping
    public ResponseEntity<List<WorkspaceListResponse>> getMyWorkspaces(
            @RequestParam Long userId
    ) {

        return ResponseEntity.ok(
                workspaceService.getMyWorkspaces(
                        userId
                )
        );
    }


    /*
     * =====================================================
     * 현재 로그인한 사용자의 워크스페이스 조회
     *
     * GET /api/workspaces/me
     * =====================================================
     */

    @GetMapping("/me")
    public ResponseEntity<List<WorkspaceListResponse>>
    getMyWorkspacesByToken(
            @AuthenticationPrincipal Long userId
    ) {

        requireLogin(userId);

        return ResponseEntity.ok(
                workspaceService.getMyWorkspaces(
                        userId
                )
        );
    }


    /*
     * =====================================================
     * 워크스페이스 생성
     *
     * POST /api/workspaces
     * =====================================================
     */

    @PostMapping
    public ResponseEntity<Workspace> createWorkspace(
            @RequestBody
            @Valid
            WorkspaceCreateRequest request
    ) {

        Workspace createdWorkspace =
                workspaceService.createWorkspace(
                        request
                );

        return ResponseEntity.ok(
                createdWorkspace
        );
    }


    /*
     * =====================================================
     * 워크스페이스 수정
     *
     * PATCH /api/workspaces/{workspaceId}
     *
     * OWNER만 가능
     * =====================================================
     */

    @PatchMapping("/{workspaceId}")
    public ResponseEntity<String> updateWorkspace(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId,
            @RequestBody
            @Valid
            WorkspaceUpdateRequest request
    ) {

        requireLogin(userId);

        workspaceService.updateWorkspace(
                workspaceId,
                userId,
                request
        );

        return ResponseEntity.ok(
                "프로젝트 정보가 수정되었습니다."
        );
    }


    /*
     * =====================================================
     * 워크스페이스 복제
     *
     * POST /api/workspaces/{workspaceId}/duplicate
     *
     * OWNER만 가능
     * =====================================================
     */

    @PostMapping("/{workspaceId}/duplicate")
    public ResponseEntity<String> duplicateWorkspace(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId,
            @RequestBody
            @Valid
            WorkspaceDuplicateRequest request
    ) {

        requireLogin(userId);

        workspaceService.duplicateWorkspace(
                workspaceId,
                userId,
                request
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        "프로젝트가 복제되었습니다."
                );
    }


    /*
     * =====================================================
     * 워크스페이스 삭제
     *
     * DELETE /api/workspaces/{workspaceId}
     *
     * OWNER만 가능
     * =====================================================
     */

    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<Void> deleteWorkspace(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId
    ) {

        requireLogin(userId);

        workspaceService.deleteWorkspace(
                workspaceId,
                userId
        );

        return ResponseEntity
                .noContent()
                .build();
    }


    /*
     * =====================================================
     * 팀 프로젝트 나가기
     *
     * DELETE /api/workspaces/{workspaceId}/leave
     *
     * MEMBER만 가능
     *
     * OWNER는 나가기 불가
     * =====================================================
     */

    @DeleteMapping("/{workspaceId}/leave")
    public ResponseEntity<Void> leaveWorkspace(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId
    ) {

        requireLogin(userId);

        workspaceService.leaveWorkspace(
                workspaceId,
                userId
        );

        return ResponseEntity
                .noContent()
                .build();
    }


    /*
     * =====================================================
     * 팀원 초대
     *
     * POST /api/workspaces/invite
     * =====================================================
     */

    @PostMapping("/invite")
    public ResponseEntity<String> inviteMember(
            @RequestBody
            @Valid
            InviteMemberRequest request
    ) {

        try {

            workspaceService.inviteMember(
                    request
            );

            return ResponseEntity.ok(
                    "팀원에게 초대가 발송되었습니다."
            );

        } catch (Exception e) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            e.getMessage()
                    );
        }
    }


    /*
     * =====================================================
     * 초대 수락
     *
     * POST /api/workspaces/{workspaceId}/accept
     * =====================================================
     */

    @PostMapping("/{workspaceId}/accept")
    public ResponseEntity<String> acceptInvitation(
            @PathVariable String workspaceId,
            @RequestParam Long userId
    ) {

        try {

            workspaceService.acceptInvitation(
                    workspaceId,
                    userId
            );

            return ResponseEntity.ok(
                    "워크스페이스 초대를 수락했습니다."
            );

        } catch (Exception e) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            e.getMessage()
                    );
        }
    }


    /*
     * =====================================================
     * 초대 거절
     *
     * POST /api/workspaces/{workspaceId}/reject
     * =====================================================
     */

    @PostMapping("/{workspaceId}/reject")
    public ResponseEntity<String> rejectInvitation(
            @PathVariable String workspaceId,
            @RequestParam Long userId
    ) {

        try {

            workspaceService.rejectInvitation(
                    workspaceId,
                    userId
            );

            return ResponseEntity.ok(
                    "워크스페이스 초대를 거절했습니다."
            );

        } catch (Exception e) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            e.getMessage()
                    );
        }
    }


    /*
     * =====================================================
     * 대기 중인 초대 목록
     *
     * GET /api/workspaces/invitations
     * =====================================================
     */

    @GetMapping("/invitations")
    public ResponseEntity<List<WorkspaceInvitationResponse>>
    getPendingInvitations(
            @RequestParam Long userId
    ) {

        return ResponseEntity.ok(
                workspaceService
                        .getPendingInvitations(
                                userId
                        )
        );
    }


    /*
     * =====================================================
     * 워크스페이스 멤버 목록
     *
     * GET /api/workspaces/{workspaceId}/members
     * =====================================================
     */

    @GetMapping("/{workspaceId}/members")
    public ResponseEntity<List<WorkspaceMemberResponse>>
    getWorkspaceMembers(
            @PathVariable String workspaceId
    ) {

        return ResponseEntity.ok(
                workspaceService
                        .getWorkspaceMembers(
                                workspaceId
                        )
        );
    }


    /*
     * =====================================================
     * 로그인 검사
     * =====================================================
     */

    private void requireLogin(
            Long userId
    ) {

        if (userId == null) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }
    }
}