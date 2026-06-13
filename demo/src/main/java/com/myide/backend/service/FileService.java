package com.myide.backend.service;

import com.myide.backend.dto.ide.FileNode;
import com.myide.backend.dto.ide.FileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class FileService {

    private final WorkspaceService workspaceService;
    private final CodeMapService codeMapService;

    private static final String DEFAULT_BRANCH_NAME = "master";

    private static final Set<String> IGNORED_FILE_TREE_NAMES = Set.of(
            ".git",
            "node_modules",
            ".next",
            "target",
            "build",
            "out",
            "dist",
            ".gradle",
            ".idea"
    );

    private String normalizeBranchName(String branchName) {
        if (branchName == null || branchName.isBlank()) {
            return DEFAULT_BRANCH_NAME;
        }

        return branchName;
    }

    private String normalizeFilePath(String filePath) {
        return String.valueOf(filePath == null ? "" : filePath)
                .replace("\\", "/")
                .replaceAll("^/+", "")
                .replaceAll("/+", "/")
                .trim();
    }

    private ResponseStatusException badFileRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFoundFileRequest(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private Path getSecureTargetPath(
            String workspaceId,
            String projectName,
            String branchName,
            String filePath
    ) {
        String normalizedBranchName = normalizeBranchName(branchName);

        Path root = workspaceService
                .getProjectPath(workspaceId, projectName, normalizedBranchName)
                .normalize();

        String normalizedFilePath = normalizeFilePath(filePath);

        if (normalizedFilePath.isBlank()) {
            throw badFileRequest("파일 경로가 비어 있습니다.");
        }

        Path target = root.resolve(normalizedFilePath).normalize();

        if (!target.startsWith(root)) {
            throw new SecurityException("잘못된 파일 접근입니다. (해킹 시도 차단)");
        }

        return target;
    }

    public FileNode getFileTree(String workspaceId, String projectName, String branchName) {
        String normalizedBranchName = normalizeBranchName(branchName);

        Path targetDir = workspaceService.getProjectPath(
                workspaceId,
                projectName,
                normalizedBranchName
        );

        if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
            FileNode emptyRoot = FileNode.builder()
                    .id("root")
                    .name(projectName)
                    .type("folder")
                    .build();
            emptyRoot.setChildren(Collections.emptyList());
            return emptyRoot;
        }

        return traverseDirectory(targetDir, targetDir, projectName);
    }

    public String getFileContent(
            String workspaceId,
            String projectName,
            String branchName,
            String filePath
    ) {
        Path target = getSecureTargetPath(
                workspaceId,
                projectName,
                branchName,
                filePath
        );

        if (!Files.exists(target)) {
            throw notFoundFileRequest("파일을 찾을 수 없습니다: " + normalizeFilePath(filePath));
        }

        if (Files.isDirectory(target)) {
            throw badFileRequest("폴더는 파일처럼 열 수 없습니다: " + normalizeFilePath(filePath));
        }

        if (!Files.isRegularFile(target)) {
            throw badFileRequest("일반 파일만 열 수 있습니다: " + normalizeFilePath(filePath));
        }

        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("파일 읽기 실패: " + e.getMessage(), e);
        }
    }

    public void createFile(FileRequest request) {
        String branchName = normalizeBranchName(request.getBranchName());

        Path target = getSecureTargetPath(
                request.getWorkspaceId(),
                request.getProjectName(),
                branchName,
                request.getFilePath()
        );

        try {
            if ("folder".equalsIgnoreCase(request.getType())) {
                Files.createDirectories(target);
            } else {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }

                Files.createFile(target);
            }

            codeMapService.invalidateCache(
                    request.getWorkspaceId(),
                    request.getProjectName(),
                    branchName
            );

        } catch (IOException e) {
            throw new RuntimeException("파일 생성 실패: " + e.getMessage(), e);
        }
    }

    public void saveFile(FileRequest request) {
        String branchName = normalizeBranchName(request.getBranchName());

        Path target = getSecureTargetPath(
                request.getWorkspaceId(),
                request.getProjectName(),
                branchName,
                request.getFilePath()
        );

        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }

            Files.writeString(target, request.getCode(), StandardCharsets.UTF_8);

            codeMapService.invalidateCache(
                    request.getWorkspaceId(),
                    request.getProjectName(),
                    branchName
            );

        } catch (IOException e) {
            throw new RuntimeException("파일 저장 실패: " + e.getMessage(), e);
        }
    }

    public void deleteFile(FileRequest request) {
        String branchName = normalizeBranchName(request.getBranchName());

        Path target = getSecureTargetPath(
                request.getWorkspaceId(),
                request.getProjectName(),
                branchName,
                request.getFilePath()
        );

        try {
            FileSystemUtils.deleteRecursively(target);

            codeMapService.invalidateCache(
                    request.getWorkspaceId(),
                    request.getProjectName(),
                    branchName
            );

        } catch (IOException e) {
            throw new RuntimeException("삭제 실패", e);
        }
    }

    public void renameFile(FileRequest request) {
        String branchName = normalizeBranchName(request.getBranchName());

        Path root = workspaceService
                .getProjectPath(
                        request.getWorkspaceId(),
                        request.getProjectName(),
                        branchName
                )
                .normalize();

        Path oldPath = root.resolve(request.getFilePath()).normalize();

        if (!oldPath.startsWith(root)) {
            throw new SecurityException("잘못된 파일 접근입니다.");
        }

        if (oldPath.getParent() == null) {
            throw new IllegalArgumentException("변경할 수 없는 경로입니다.");
        }

        Path newPath = oldPath.getParent().resolve(request.getNewName()).normalize();

        if (!newPath.startsWith(root)) {
            throw new SecurityException("잘못된 파일 접근입니다.");
        }

        try {
            Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);

            codeMapService.invalidateCache(
                    request.getWorkspaceId(),
                    request.getProjectName(),
                    branchName
            );

        } catch (IOException e) {
            throw new RuntimeException("이름 변경 실패", e);
        }
    }

    private FileNode traverseDirectory(Path dir, Path rootDir, String rootName) {
        String relativePath = rootDir.relativize(dir).toString().replace("\\", "/");
        String displayName = relativePath.isEmpty()
                ? rootName
                : dir.getFileName().toString();

        String id = relativePath.isEmpty() ? "root" : relativePath;

        FileNode node = FileNode.builder()
                .id(id)
                .name(displayName)
                .type("folder")
                .build();

        try (Stream<Path> stream = Files.list(dir)) {
            List<FileNode> children = stream
                    .filter(path -> !IGNORED_FILE_TREE_NAMES.contains(path.getFileName().toString()))
                    .map(path -> {
                        if (Files.isDirectory(path)) {
                            return traverseDirectory(path, rootDir, null);
                        }

                        return FileNode.builder()
                                .id(rootDir.relativize(path).toString().replace("\\", "/"))
                                .name(path.getFileName().toString())
                                .type("file")
                                .build();
                    })
                    .collect(Collectors.toList());

            node.setChildren(children);

        } catch (IOException e) {
            node.setChildren(Collections.emptyList());
        }

        return node;
    }
}