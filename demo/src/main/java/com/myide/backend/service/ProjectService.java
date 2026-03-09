package com.myide.backend.service;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.Workspace;
import com.myide.backend.dto.CreateProjectRequest;
import com.myide.backend.dto.FileNode;
import com.myide.backend.dto.FileRequest;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final GitService gitService;

    @Transactional(readOnly = true)
    public FileNode getProjectList(String workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("Workspace not found"));

        Map<String, String> dbProjectMap = workspace.getProjects().stream()
                .collect(Collectors.toMap(Project::getName, p -> p.getGitUrl() != null ? p.getGitUrl() : "", (e, r) -> e));

        Path workspaceRoot = Paths.get(workspace.getPath());
        FileNode rootNode = FileNode.builder().id("root").name("Projects").type("folder").build();

        if (!Files.exists(workspaceRoot)) return rootNode;

        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            List<FileNode> projects = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith(".") && !path.getFileName().toString().equals("temp"))
                    .map(path -> {
                        String pName = path.getFileName().toString();
                        return FileNode.builder().id(pName).name(pName).type("project").gitUrl(dbProjectMap.getOrDefault(pName, null)).build();
                    }).collect(Collectors.toList());
            rootNode.setChildren(projects);
        } catch (IOException e) { rootNode.setChildren(Collections.emptyList()); }
        return rootNode;
    }

    public void createNewProject(CreateProjectRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId()).orElseThrow();
        Path projectRoot = Paths.get(workspace.getPath(), request.getProjectName());

        // 💡 [수정됨] 프로젝트의 메인 폴더 이름을 'master'로 생성합니다!
        Path masterRepoPath = projectRoot.resolve("master");

        try {
            if (Files.exists(projectRoot)) throw new RuntimeException("이미 존재하는 프로젝트입니다.");
            Files.createDirectories(masterRepoPath);
            createTemplateFiles(masterRepoPath, request.getLanguage());
            gitService.createRepository(masterRepoPath);

            if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                gitService.addRemote(masterRepoPath, request.getGitUrl());
            }

            projectRepository.save(Project.builder()
                    .name(request.getProjectName()).description(request.getDescription())
                    .language(request.getLanguage()).gitUrl(request.getGitUrl()).workspace(workspace).build());
        } catch (IOException e) { throw new RuntimeException("프로젝트 생성 실패: " + e.getMessage()); }
    }

    @Transactional
    public void updateProjectGitUrl(String workspaceId, String projectName, String gitUrl) {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow();
        Project project = projectRepository.findAll().stream()
                .filter(p -> p.getWorkspace().getUuid().equals(workspaceId) && p.getName().equals(projectName))
                .findFirst().orElseThrow();

        project.setGitUrl(gitUrl);
        project.setUpdatedAt(LocalDateTime.now());
        projectRepository.save(project);

        // 💡 [수정됨] master 폴더 경로로 잡기
        Path masterRepoPath = Paths.get(workspace.getPath(), projectName).resolve("master");
        gitService.addRemote(masterRepoPath, gitUrl);
    }

    public void createBranch(FileRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId()).orElseThrow();
        Path projectRoot = Paths.get(workspace.getPath(), request.getProjectName());

        // 💡 [수정됨] 기준 폴더를 master로 설정
        Path masterRepoPath = projectRoot.resolve("master");
        Path worktreePath = projectRoot.resolve(request.getBranchName());

        if (!Files.exists(masterRepoPath)) throw new RuntimeException("메인 저장소를 찾을 수 없습니다.");
        if (Files.exists(worktreePath)) throw new RuntimeException("이미 존재하는 브랜치입니다.");

        gitService.createWorktree(masterRepoPath, worktreePath, request.getBranchName());
    }

    public List<String> getBranchList(String workspaceId, String projectName) {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow();
        Path projectPath = Paths.get(workspace.getPath(), projectName);
        List<String> branches = new ArrayList<>();

        if (Files.exists(projectPath) && Files.isDirectory(projectPath)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectPath)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry) && !entry.getFileName().toString().startsWith(".")) {
                        branches.add(entry.getFileName().toString());
                    }
                }
            } catch (IOException e) { throw new RuntimeException("브랜치 목록 조회 실패", e); }
        }
        return branches;
    }

    private void createTemplateFiles(Path path, LanguageType lang) throws IOException {
        Files.writeString(path.resolve("README.md"), "# " + lang.name() + " Project");
        if (lang.getDefaultFileName() != null) Files.writeString(path.resolve(lang.getDefaultFileName()), lang.getDefaultCode());
        if (lang == LanguageType.CSHARP) {
            Files.writeString(path.resolve("Project.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk\">\n<PropertyGroup>\n<OutputType>Exe</OutputType>\n<TargetFramework>net8.0</TargetFramework>\n</PropertyGroup>\n</Project>");
        }
    }
}