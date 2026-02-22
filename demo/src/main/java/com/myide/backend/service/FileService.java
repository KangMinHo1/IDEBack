package com.myide.backend.service;

import com.myide.backend.dto.FileNode;
import com.myide.backend.dto.FileRequest;
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

    private final WorkspaceService workspaceService; // 경로 계산을 위해 주입

    public FileNode getFileTree(String workspaceId, String projectName, String branchName) {
        Path targetDir = workspaceService.getProjectPath(workspaceId, projectName, branchName);
        if (!Files.exists(targetDir)) return FileNode.builder().id(projectName).name(projectName).type("project").build();
        return traverseDirectory(targetDir, targetDir, projectName);
    }

    public String getFileContent(String workspaceId, String projectName, String branchName, String filePath) {
        Path target = workspaceService.getProjectPath(workspaceId, projectName, branchName).resolve(filePath);
        try { return Files.readString(target, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    public void createFile(FileRequest request) {
        Path root = workspaceService.getProjectPath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
        Path target = root.resolve(request.getFilePath());
        try {
            if ("folder".equalsIgnoreCase(request.getType())) Files.createDirectories(target);
            else {
                if(target.getParent() != null) Files.createDirectories(target.getParent());
                Files.createFile(target);
            }
        } catch (IOException e) { throw new RuntimeException("파일 생성 실패: " + e.getMessage()); }
    }

    public void saveFile(FileRequest request) {
        Path root = workspaceService.getProjectPath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
        Path target = root.resolve(request.getFilePath());
        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) Files.createDirectories(target.getParent());
            Files.writeString(target, request.getCode(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new RuntimeException("파일 저장 실패: " + e.getMessage()); }
    }

    public void deleteFile(FileRequest request) {
        Path target = workspaceService.getProjectPath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName()).resolve(request.getFilePath());
        try { FileSystemUtils.deleteRecursively(target); }
        catch (IOException e) { throw new RuntimeException("삭제 실패", e); }
    }

    public void renameFile(FileRequest request) {
        Path root = workspaceService.getProjectPath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
        Path oldPath = root.resolve(request.getFilePath());
        Path newPath = oldPath.getParent().resolve(request.getNewName());
        try { Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException e) { throw new RuntimeException("이름 변경 실패", e); }
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
        } catch (IOException e) { node.setChildren(Collections.emptyList()); }
        return node;
    }
}