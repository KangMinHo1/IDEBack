package com.myide.backend.service;

import com.myide.backend.domain.Workspace;
import com.myide.backend.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private static final String DEFAULT_ROOT = "C:\\WebIDE\\workspaces";
    private final WorkspaceRepository workspaceRepository;

    // 💡 [핵심] 온 동네 서비스들이 경로를 물어보러 오는 마스터 메서드입니다!
    public Path getProjectPath(String workspaceId, String projectName, String branchName) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다: " + workspaceId));

        // 💡 브랜치명 통일 로직 (빈 값이거나 넘어오지 않으면 기본값 'master' 적용)
        String realBranchFolder = (branchName == null || branchName.isBlank() || "master".equals(branchName) || "main-repo".equals(branchName))
                ? "master" : branchName;

        // 완벽한 절대 경로 조립 후 반환
        return Paths.get(workspace.getPath(), projectName, realBranchFolder).toAbsolutePath();
    }

    public Workspace createWorkspace(String userId, String name, String customPath) {
        String uuid = UUID.randomUUID().toString();
        Path rootPath = (customPath != null && !customPath.isBlank())
                ? Paths.get(customPath, name) : Paths.get(DEFAULT_ROOT, name);

        try {
            if (Files.exists(rootPath)) throw new RuntimeException("이미 존재하는 워크스페이스입니다.");
            Files.createDirectories(rootPath);
            return workspaceRepository.save(Workspace.builder()
                    .uuid(uuid).name(name).ownerId(userId)
                    .path(rootPath.toAbsolutePath().toString()).build());
        } catch (IOException e) {
            throw new RuntimeException("워크스페이스 생성 실패: " + e.getMessage());
        }
    }

    public List<Workspace> getMyWorkspaces(String userId) {
        return workspaceRepository.findAll().stream()
                .filter(w -> w.getOwnerId().equals(userId))
                .collect(Collectors.toList());
    }


    public Path getWorkspaceRootPath(String workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다: " + workspaceId));
        return Paths.get(workspace.getPath()).toAbsolutePath();
    }
}