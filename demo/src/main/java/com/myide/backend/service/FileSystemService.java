package com.myide.backend.service;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.Workspace;
import com.myide.backend.dto.*;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileSystemService {

    private static final String DEFAULT_ROOT = "C:\\WebIDE\\workspaces";

    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final DockerService dockerService;
    private final GitService gitService;

    // 경로 계산 로직
    private Path calculatePath(String workspaceId, String projectName, String branchName) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다: " + workspaceId));

        // [중요] activeBranch가 null/blank/"main" 이면 무조건 main-repo 폴더 사용
        String realBranchFolder = (branchName == null || branchName.isBlank() ||
                "main-repo".equals(branchName) || "main".equals(branchName))
                ? "main-repo" : branchName;

        return Paths.get(workspace.getPath(), projectName, realBranchFolder);
    }

    // [수정] 워크스페이스 생성 (이름 기반 폴더 생성)
    public Workspace createWorkspace(String userId, String name, String customPath) {
        String uuid = UUID.randomUUID().toString();
        Path rootPath;

        // 1. 사용자 지정 경로가 있으면 사용, 없으면 기본 경로 + "워크스페이스 이름"
        if (customPath != null && !customPath.isBlank()) {
            rootPath = Paths.get(customPath, name);
        } else {
            rootPath = Paths.get(DEFAULT_ROOT, name); // [변경] uuid -> name
        }

        try {
            // 중복 이름 체크
            if (Files.exists(rootPath)) {
                throw new RuntimeException("이미 존재하는 워크스페이스 폴더입니다: " + name);
            }

            Files.createDirectories(rootPath);

            return workspaceRepository.save(Workspace.builder()
                    .uuid(uuid)
                    .name(name)
                    .ownerId(userId)
                    .path(rootPath.toAbsolutePath().toString()) // 실제 생성된 경로 저장
                    .build());
        } catch (IOException e) { throw new RuntimeException("워크스페이스 폴더 생성 실패: " + e.getMessage()); }
    }

    public List<Workspace> getMyWorkspaces(String userId) {
        return workspaceRepository.findAll().stream()
                .filter(w -> w.getOwnerId().equals(userId))
                .collect(Collectors.toList());
    }

    // 프로젝트 목록 조회 (DB 정보 병합)
    @Transactional(readOnly = true)
    public FileNode getProjectList(String workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("Workspace not found"));

        Map<String, String> dbProjectMap = workspace.getProjects().stream()
                .collect(Collectors.toMap(
                        Project::getName,
                        p -> p.getGitUrl() != null ? p.getGitUrl() : "",
                        (existing, replacement) -> existing
                ));

        Path workspaceRoot = Paths.get(workspace.getPath());
        FileNode rootNode = FileNode.builder().id("root").name("Projects").type("folder").build();

        if (!Files.exists(workspaceRoot)) return rootNode;

        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            List<FileNode> projects = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> !path.getFileName().toString().equals("temp"))
                    .map(path -> {
                        String pName = path.getFileName().toString();
                        String gitUrl = dbProjectMap.getOrDefault(pName, null);
                        return FileNode.builder().id(pName).name(pName).type("project").gitUrl(gitUrl).build();
                    })
                    .collect(Collectors.toList());
            rootNode.setChildren(projects);
        } catch (IOException e) {
            rootNode.setChildren(Collections.emptyList());
        }
        return rootNode;
    }

    // 프로젝트 생성
    public void createNewProject(CreateProjectRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new RuntimeException("Workspace not found"));

        Path projectRoot = Paths.get(workspace.getPath(), request.getProjectName());
        Path mainRepoPath = projectRoot.resolve("main-repo");

        try {
            if (Files.exists(projectRoot)) throw new RuntimeException("이미 존재하는 프로젝트입니다.");
            Files.createDirectories(mainRepoPath);

            createTemplateFiles(mainRepoPath, request.getLanguage());
            gitService.createRepository(mainRepoPath);

            if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                gitService.addRemote(mainRepoPath, request.getGitUrl());
            }

            Project project = Project.builder()
                    .name(request.getProjectName())
                    .description(request.getDescription())
                    .language(request.getLanguage())
                    .gitUrl(request.getGitUrl())
                    .workspace(workspace)
                    .build();

            projectRepository.save(project);

        } catch (IOException e) {
            throw new RuntimeException("프로젝트 생성 실패: " + e.getMessage());
        }
    }

    // Git URL 업데이트
    @Transactional
    public void updateProjectGitUrl(String workspaceId, String projectName, String gitUrl) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("Workspace not found"));

        Project project = projectRepository.findAll().stream()
                .filter(p -> p.getWorkspace().getUuid().equals(workspaceId) && p.getName().equals(projectName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Project not found: " + projectName));

        Project updatedProject = new Project(
                project.getId(), project.getName(), project.getDescription(),
                project.getLanguage(), gitUrl, project.getWorkspace()
        );
        projectRepository.save(updatedProject);

        Path projectRoot = Paths.get(workspace.getPath(), projectName);
        Path mainRepoPath = projectRoot.resolve("main-repo");

        try {
            gitService.addRemote(mainRepoPath, gitUrl);
        } catch (Exception e) {
            throw new RuntimeException("Git Remote 연결 실패: " + e.getMessage());
        }
    }

    public void createBranch(FileRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new RuntimeException("Workspace not found"));

        Path projectRoot = Paths.get(workspace.getPath(), request.getProjectName());
        Path mainRepoPath = projectRoot.resolve("main-repo");
        Path worktreePath = projectRoot.resolve(request.getBranchName());

        if (!Files.exists(mainRepoPath)) throw new RuntimeException("메인 저장소를 찾을 수 없습니다.");
        if (Files.exists(worktreePath)) throw new RuntimeException("이미 존재하는 브랜치입니다.");

        gitService.createWorktree(mainRepoPath, worktreePath, request.getBranchName());
    }

    public FileNode getFileTree(String workspaceId, String projectName, String branchName) {
        Path targetDir = calculatePath(workspaceId, projectName, branchName);
        if (!Files.exists(targetDir)) return FileNode.builder().id(projectName).name(projectName).type("project").build();
        return traverseDirectory(targetDir, targetDir, projectName);
    }

    public String getFileContent(String workspaceId, String projectName, String branchName, String filePath) {
        Path target = calculatePath(workspaceId, projectName, branchName).resolve(filePath);
        try { return Files.readString(target, StandardCharsets.UTF_8); } catch (IOException e) { throw new RuntimeException(e); }
    }

    public void createFile(FileRequest request) {
        Path root = calculatePath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
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
        Path root = calculatePath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
        Path target = root.resolve(request.getFilePath());
        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) Files.createDirectories(target.getParent());
            Files.writeString(target, request.getCode(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new RuntimeException("파일 저장 실패: " + e.getMessage()); }
    }

    public void deleteFile(FileRequest request) {
        Path target = calculatePath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName()).resolve(request.getFilePath());
        try { FileSystemUtils.deleteRecursively(target); } catch (IOException e) { throw new RuntimeException("삭제 실패", e); }
    }

    public void renameFile(FileRequest request) {
        Path root = calculatePath(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
        Path oldPath = root.resolve(request.getFilePath());
        Path newPath = oldPath.getParent().resolve(request.getNewName());
        try { Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) { throw new RuntimeException("이름 변경 실패", e); }
    }

    public String buildProject(BuildRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new RuntimeException("Workspace not found"));

        String projectName = request.getProjectName();
        String branchFolder = (request.getBranchName() == null || request.getBranchName().isEmpty() || "main".equals(request.getBranchName()))
                ? "main-repo" : request.getBranchName();
        String outputFileName = projectName + ".jar";

        String cmd = String.format("cd %s/%s && javac -encoding UTF-8 *.java && jar cfe %s Main *.class", projectName, branchFolder, outputFileName);
        String containerFilePath = "/app/" + projectName + "/" + branchFolder + "/" + outputFileName;

        Path tempDir = Paths.get(workspace.getPath(), ".temp");
        try { if (!Files.exists(tempDir)) Files.createDirectories(tempDir); } catch(IOException e){}
        String hostFilePath = tempDir.resolve(outputFileName).toString();

        dockerService.buildAndCopy(request.getWorkspaceId(), cmd, containerFilePath, hostFilePath);
        return hostFilePath;
    }

    private void createTemplateFiles(Path path, LanguageType lang) throws IOException {
        String fileName = "README.txt";
        String content = "Project Created.";
        if (lang == LanguageType.JAVA) { fileName = "Main.java"; content = "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello Java!\");\n    }\n}"; }
        else if (lang == LanguageType.PYTHON) { fileName = "main.py"; content = "print('Hello Python!')"; }
        Files.writeString(path.resolve(fileName), content);
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