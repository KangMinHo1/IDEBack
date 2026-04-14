package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import com.myide.backend.domain.workspace.WorkspaceType;
import com.myide.backend.dto.workspace.InviteMemberRequest;
import com.myide.backend.dto.workspace.WorkspaceCreateRequest;
import com.myide.backend.dto.workspace.WorkspaceInvitationResponse;
import com.myide.backend.dto.workspace.WorkspaceListResponse;
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
import java.util.Comparator;
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

            Long ownerLongId;
            try {
                ownerLongId = Long.valueOf(request.getUserId());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("워크스페이스 생성 실패: 올바른 숫자형 회원 ID가 필요합니다.");
            }

            User ownerUser = userRepository.findById(ownerLongId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

            Workspace newWorkspace;

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

    @Transactional(readOnly = true)
    public List<WorkspaceInvitationResponse> getPendingInvitations(Long userId) {
        List<WorkspaceMember> pendingMembers = workspaceMemberRepository.findByUser_IdAndStatus(
                userId,
                WorkspaceMember.JoinStatus.PENDING
        );

        return pendingMembers.stream()
                .map(member -> WorkspaceInvitationResponse.builder()
                        .workspaceId(member.getWorkspace().getUuid())
                        .workspaceName(member.getWorkspace().getName())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> getWorkspaceMembers(String workspaceId) {
        List<WorkspaceMember> members = workspaceMemberRepository.findByWorkspace_UuidAndStatus(
                workspaceId,
                WorkspaceMember.JoinStatus.ACCEPTED
        );

        return members.stream()
                .map(member -> WorkspaceMemberResponse.builder()
                        .userId(member.getUser().getId())
                        .email(member.getUser().getEmail())
                        .nickname(member.getUser().getNickname())
                        .role(member.getRole().name())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WorkspaceListResponse> getMyWorkspaces(Long userId) {
        return workspaceRepository.findMyAllWorkspaces(userId).stream()
                .sorted(Comparator.comparing(
                        Workspace::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                // ✅ 현재 사용자 기준 role 계산을 위해 userId 전달
                .map(workspace -> WorkspaceListResponse.from(workspace, userId))
                .collect(Collectors.toList());
    }

    public Path getWorkspaceRootPath(String workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다: " + workspaceId));
        return Paths.get(workspace.getPath()).toAbsolutePath();
    }
}