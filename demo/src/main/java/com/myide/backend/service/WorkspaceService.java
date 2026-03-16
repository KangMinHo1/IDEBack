package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import com.myide.backend.domain.workspace.WorkspaceType;
import com.myide.backend.dto.workspace.InviteMemberRequest;
import com.myide.backend.dto.workspace.WorkspaceCreateRequest;
import com.myide.backend.dto.workspace.WorkspaceInvitationResponse;
import com.myide.backend.dto.workspace.WorkspaceMemberResponse;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.workspace.WorkspaceMemberRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private static final String DEFAULT_ROOT = "C:\\WebIDE\\workspaces";
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;

    public Path getProjectPath(String workspaceId, String projectName, String branchName) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다: " + workspaceId));

        String realBranchFolder = (branchName == null || branchName.isBlank() || "master".equals(branchName) || "main-repo".equals(branchName))
                ? "master" : branchName;

        return Paths.get(workspace.getPath(), projectName, realBranchFolder).toAbsolutePath();
    }

    @Transactional
    public Workspace createWorkspace(WorkspaceCreateRequest request) {
        Path rootPath = (request.getPath() != null && !request.getPath().isBlank())
                ? Paths.get(request.getPath(), request.getName())
                : Paths.get(DEFAULT_ROOT, request.getName());

        if (Files.exists(rootPath)) {
            throw new RuntimeException("이미 존재하는 워크스페이스 경로입니다.");
        }

        try {
            Files.createDirectories(rootPath);
            String absolutePath = rootPath.toAbsolutePath().toString();

            // 💡 [핵심 변경] 프론트에서 온 ID로 생성자(User) 객체를 가장 먼저 찾아옵니다.
            Long ownerLongId;
            try {
                ownerLongId = Long.valueOf(request.getUserId());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("워크스페이스 생성 실패: 올바른 숫자형 회원 ID가 필요합니다.");
            }

            User ownerUser = userRepository.findById(ownerLongId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

            Workspace newWorkspace;

            // 💡 찾아온 User 객체를 엔티티 생성 시 넘겨줍니다.
            if (request.getType() == WorkspaceType.TEAM) {
                newWorkspace = Workspace.createTeam(ownerUser, request.getName(), request.getDescription(), absolutePath);
                Workspace savedWorkspace = workspaceRepository.save(newWorkspace);

                WorkspaceMember owner = WorkspaceMember.createOwner(savedWorkspace, ownerUser);
                workspaceMemberRepository.save(owner);

                return savedWorkspace;
            } else {
                newWorkspace = Workspace.createPersonal(ownerUser, request.getName(), request.getDescription(), absolutePath);
                return workspaceRepository.save(newWorkspace);
            }

        } catch (Exception e) {
            try {
                if (Files.exists(rootPath)) {
                    FileSystemUtils.deleteRecursively(rootPath);
                }
            } catch (IOException ioException) {
                System.err.println("디렉토리 롤백 실패: " + ioException.getMessage());
            }
            throw new RuntimeException("워크스페이스 생성 중 오류 발생.", e);
        }
    }

    @Transactional
    public void inviteMember(InviteMemberRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 워크스페이스입니다."));

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("해당 이메일로 가입된 유저가 없습니다."));

        if (workspaceMemberRepository.existsByWorkspaceAndUser(workspace, user)) {
            throw new IllegalStateException("이미 초대되었거나 가입된 멤버입니다.");
        }

        WorkspaceMember member = WorkspaceMember.builder()
                .workspace(workspace)
                .user(user)
                .role(WorkspaceMember.WorkspaceRole.MEMBER)
                .status(WorkspaceMember.JoinStatus.PENDING)
                .build();

        workspaceMemberRepository.save(member);
    }

    @Transactional
    public void acceptInvitation(String workspaceId, Long userId) {
        WorkspaceMember member = workspaceMemberRepository.findByWorkspace_UuidAndUser_Id(workspaceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("초대 내역이 존재하지 않습니다."));

        if (member.getStatus() == WorkspaceMember.JoinStatus.ACCEPTED) {
            throw new IllegalStateException("이미 가입이 완료된 워크스페이스입니다.");
        }

        member.acceptInvitation();
    }

    @Transactional
    public void rejectInvitation(String workspaceId, Long userId) {
        WorkspaceMember member = workspaceMemberRepository.findByWorkspace_UuidAndUser_Id(workspaceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("초대 내역이 존재하지 않습니다."));

        workspaceMemberRepository.delete(member);
    }

    // =========================================================================
    // 💡 [신규 추가] 나에게 온 대기 중인 초대 목록 조회 로직
    // =========================================================================
    @Transactional(readOnly = true)
    public List<WorkspaceInvitationResponse> getPendingInvitations(Long userId) {
        // 1. 유저 ID와 'PENDING(대기 중)' 상태를 기준으로 멤버 테이블 조회
        List<WorkspaceMember> pendingMembers = workspaceMemberRepository.findByUser_IdAndStatus(userId, WorkspaceMember.JoinStatus.PENDING);

        // 2. 조회된 엔티티 리스트를 프론트엔드가 렌더링하기 편한 DTO 리스트로 변환
        return pendingMembers.stream()
                .map(member -> WorkspaceInvitationResponse.builder()
                        .workspaceId(member.getWorkspace().getUuid())
                        .workspaceName(member.getWorkspace().getName())
                        .build())
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 💡 [신규 추가] 특정 워크스페이스의 실제 참여 팀원 목록 조회
    // =========================================================================
    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> getWorkspaceMembers(String workspaceId) {
        // 해당 워크스페이스에서 'ACCEPTED(수락됨)' 상태인 멤버만 가져옵니다. (방장도 포함)
        List<WorkspaceMember> members = workspaceMemberRepository.findByWorkspace_UuidAndStatus(workspaceId, WorkspaceMember.JoinStatus.ACCEPTED);

        return members.stream()
                .map(member -> WorkspaceMemberResponse.builder()
                        .userId(member.getUser().getId())
                        .email(member.getUser().getEmail())
                        .nickname(member.getUser().getNickname())
                        .role(member.getRole().name())
                        .build())
                .collect(Collectors.toList());
    }

    // 💡 [수정] String이 아닌 Long 타입으로 파라미터를 변경하고 try-catch를 걷어냅니다.
    public List<Workspace> getMyWorkspaces(Long userId) {
        return workspaceRepository.findMyAllWorkspaces(userId);
    }

    public Path getWorkspaceRootPath(String workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다: " + workspaceId));
        return Paths.get(workspace.getPath()).toAbsolutePath();
    }
}