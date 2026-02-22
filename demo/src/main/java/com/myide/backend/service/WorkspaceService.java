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

    // [핵심 공통 로직] DB를 뒤져서 특정 프로젝트/브랜치의 실제 디스크 경로를 알아냅니다.
    public Path getProjectPath(String workspaceId, String projectName, String branchName) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다: " + workspaceId));

        String realBranchFolder = (branchName == null || branchName.isBlank() ||
                "main-repo".equals(branchName) || "main".equals(branchName))
                ? "main-repo" : branchName;

        return Paths.get(workspace.getPath(), projectName, realBranchFolder);
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
}