package com.myide.backend.service;

import com.myide.backend.dto.ide.FileNode;
import com.myide.backend.dto.ide.FileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class FileService {

    private final WorkspaceService workspaceService;
    private final CodeMapService codeMapService;

    // 💡 [보안] 안전한 경로인지 검사해주는 공용 메서드 (해킹 방지)
    private Path getSecureTargetPath(String workspaceId, String projectName, String branchName, String filePath) {
        Path root = workspaceService.getProjectPath(workspaceId, projectName, branchName);
        Path target = root.resolve(filePath).normalize(); // 경로 깔끔하게 정리 (.. 등 제거)

        // 타겟 경로가 root 폴더 안에 있는게 아니면(탈출 시도면) 에러!
        if (!target.startsWith(root)) {
            throw new SecurityException("잘못된 파일 접근입니다. (해킹 시도 차단)");
        }
        return target;
    }

    public FileNode getFileTree(String workspaceId, String projectName, String branchName) {
        Path targetDir = workspaceService.getProjectPath(workspaceId, projectName, branchName);
        if (!Files.exists(targetDir)) return FileNode.builder().id(projectName).name(projectName).type("project").build();
        return traverseDirectory(targetDir, targetDir, projectName);
    }

    public String getFileContent(String workspaceId, String projectName, String branchName, String filePath) {
        Path target = getSecureTargetPath(workspaceId, projectName, branchName, filePath);
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void createFile(FileRequest request) {
        Path target = getSecureTargetPath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName(), request.getFilePath());
        try {
            if ("folder".equalsIgnoreCase(request.getType())) {
                Files.createDirectories(target);
            } else {
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.createFile(target);
            }
            // 파일 생성 시 캐시 무효화
            codeMapService.invalidateCache(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
        } catch (IOException e) {
            throw new RuntimeException("파일 생성 실패: " + e.getMessage());
        }
    }

    public void saveFile(FileRequest request) {
        Path target = getSecureTargetPath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName(), request.getFilePath());
        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, request.getCode(), StandardCharsets.UTF_8);

            // 파일 저장 시 캐시 무효화
            codeMapService.invalidateCache(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
        } catch (IOException e) {
            throw new RuntimeException("파일 저장 실패: " + e.getMessage());
        }
    }

    public void deleteFile(FileRequest request) {
        Path target = getSecureTargetPath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName(), request.getFilePath());
        try {
            FileSystemUtils.deleteRecursively(target);

            // 파일 삭제 시 캐시 무효화
            codeMapService.invalidateCache(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
        } catch (IOException e) {
            throw new RuntimeException("삭제 실패", e);
        }
    }

    public void renameFile(FileRequest request) {
        Path root = workspaceService.getProjectPath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());

        // 💡 기존 경로와 새 경로 모두 안전한지 검사
        Path oldPath = root.resolve(request.getFilePath()).normalize();
        if (!oldPath.startsWith(root)) throw new SecurityException("잘못된 파일 접근입니다.");

        Path newPath = oldPath.getParent().resolve(request.getNewName()).normalize();
        if (!newPath.startsWith(root)) throw new SecurityException("잘못된 파일 접근입니다.");

        try {
            Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);

            // 파일명 변경 시 캐시 무효화
            codeMapService.invalidateCache(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
        } catch (IOException e) {
            throw new RuntimeException("이름 변경 실패", e);
        }
    }

    private FileNode traverseDirectory(Path dir, Path rootDir, String rootName) {
        String relativePath = rootDir.relativize(dir).toString().replace("\\", "/");
        String displayName = relativePath.isEmpty() ? rootName : dir.getFileName().toString();
        String id = relativePath.isEmpty() ? "root" : relativePath;

        FileNode node = FileNode.builder().id(id).name(displayName).type("folder").build();

        try (Stream<Path> stream = Files.list(dir)) {
            List<FileNode> children = stream
                    .filter(path -> !path.getFileName().toString().equals(".git"))
                    .map(path -> {
                        if (Files.isDirectory(path)) return traverseDirectory(path, rootDir, null);
                        else return FileNode.builder().id(rootDir.relativize(path).toString().replace("\\", "/")).name(path.getFileName().toString()).type("file").build();
                    }).collect(Collectors.toList());
            node.setChildren(children);
        } catch (IOException e) {
            node.setChildren(Collections.emptyList());
        }
        return node;
    }
}