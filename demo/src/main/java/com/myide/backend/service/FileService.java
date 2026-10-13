package com.myide.backend.service;

import com.myide.backend.dto.ide.FileNode;
import com.myide.backend.dto.ide.FileRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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

    /**
     * @return 실제로 디스크에 썼으면 true. 아래 방어에 걸려 쓰지 않았으면 false.
     *         부르는 쪽이 "저장된 내용"을 근거로 무언가 할 때(문법 검사 등) 구분해야 한다.
     */
    public boolean saveFile(FileRequest request) {
        String branchName = normalizeBranchName(request.getBranchName());

        Path target = getSecureTargetPath(
                request.getWorkspaceId(),
                request.getProjectName(),
                branchName,
                request.getFilePath()
        );

        try {
            // 동시편집의 자동 저장은 없는 파일을 새로 만들지 않는다.
            //
            // 팀원이 파일을 지우면 그 파일을 열어 둔 다른 사람의 탭이 닫히는데, 닫히면서
            // 아직 저장하지 않은 내용을 마지막으로 한 번 저장한다. 그 저장이 방금 지운
            // 파일을 되살렸다. 이름을 바꿨을 때 옛 이름의 파일이 다시 생기는 것도 같은
            // 원인이다.
            //
            // 자동 저장만 allowEmpty=false 를 실어 보내므로 그것으로 가린다. 파일은 항상
            // createFile 로 먼저 만들어진 뒤에 열리므로, 자동 저장이 없는 파일에 쓰는
            // 것은 "지워진 파일"뿐이다. 사용자가 직접 누른 저장과 실행 전 저장은 이
            // 값이 없거나 true 라 지금처럼 파일을 만든다.
            if (Boolean.FALSE.equals(request.getAllowEmpty()) && !Files.exists(target)) {
                log.warn("⚠️ [File] 지워진 파일에 대한 자동 저장을 막았습니다: {}", target);
                return false;
            }

            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }

            String code = request.getCode() == null ? "" : request.getCode();

            // 내용이 있는 파일을 빈 내용으로 덮어쓰지 않는다.
            //
            // 동시편집은 협업 서버가 문서를 보관하지 않아서, 방금 접속한
            // 사람의 문서는 먼저 있던 사람이 답을 보내 줘야 채워진다. 그
            // 전에 자동 저장이 돌면 빈 문서가 그대로 디스크에 쓰여 파일이
            // 통째로 날아간다. 프론트에서도 막지만, 저장은 되돌릴 수 없어
            // 서버에서 한 번 더 막는다.
            //
            // 오류로 돌려주지 않고 조용히 넘기는 이유는, 이 요청 자체가
            // 클라이언트의 실수라서 사용자가 손쓸 수 있는 것이 없기 때문이다.
            // 대신 서버 로그에 남긴다. 정말 비우려는 저장은 allowEmpty 를
            // 실어 보내므로 여기 걸리지 않는다.
            if (code.isEmpty()
                    && !Boolean.TRUE.equals(request.getAllowEmpty())
                    && Files.exists(target)
                    && Files.size(target) > 0) {

                log.warn("⚠️ [File] 빈 내용 저장을 막았습니다: {}", target);
                return false;
            }

            Files.writeString(target, code, StandardCharsets.UTF_8);

            codeMapService.invalidateCache(
                    request.getWorkspaceId(),
                    request.getProjectName(),
                    branchName
            );

            return true;

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