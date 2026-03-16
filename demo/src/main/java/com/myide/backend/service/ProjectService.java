package com.myide.backend.service;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.project.CreateProjectRequest;
import com.myide.backend.dto.ide.FileNode;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

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

    // 💡 1. 트랜잭션 보장 필수!
    @Transactional
    public void createNewProject(CreateProjectRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다."));

        Path projectRoot = Paths.get(workspace.getPath(), request.getProjectName());
        Path masterRepoPath = projectRoot.resolve("master");

        if (Files.exists(projectRoot)) {
            throw new RuntimeException("이미 존재하는 프로젝트입니다.");
        }

        try {
            // 1. 파일 시스템 작업 (폴더, 템플릿, Git 초기화)
            Files.createDirectories(masterRepoPath);
            createTemplateFiles(masterRepoPath, request.getLanguage());
            gitService.createRepository(masterRepoPath);

            if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                gitService.addRemote(masterRepoPath, request.getGitUrl());
            }

            // 2. DB 저장 작업
            projectRepository.save(Project.builder()
                    .name(request.getProjectName())
                    .description(request.getDescription())
                    .language(request.getLanguage())
                    .gitUrl(request.getGitUrl())
                    .workspace(workspace)
                    .build());

        } catch (Exception e) {
            // 💡 2. 에러 발생 시 수동 파일 롤백 로직 추가!
            try {
                if (Files.exists(projectRoot)) {
                    FileSystemUtils.deleteRecursively(projectRoot); // 프로젝트 폴더 전체 날리기
                }
            } catch (IOException ioException) {
                System.err.println("프로젝트 폴더 롤백 실패: " + ioException.getMessage());
            }
            throw new RuntimeException("프로젝트 생성 중 DB/Git 오류 발생. 파일 롤백됨.", e);
        }
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