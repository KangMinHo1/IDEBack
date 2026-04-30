package com.myide.backend.service;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.workspace.TemplateType;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.project.CreateProjectRequest;
import com.myide.backend.dto.ide.FileNode;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import com.myide.backend.service.template.ProjectTemplateStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final GitService gitService;
    private final List<ProjectTemplateStrategy> templateStrategies;

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
                    // 💡 [영구 예방 방어막] 하드디스크에 폴더가 있어도, DB에 없으면(유령 폴더면) 싹 무시합니다!
                    .filter(path -> dbProjectMap.containsKey(path.getFileName().toString()))
                    .map(path -> {
                        String pName = path.getFileName().toString();
                        return FileNode.builder().id(pName).name(pName).type("project").gitUrl(dbProjectMap.getOrDefault(pName, null)).build();
                    }).collect(Collectors.toList());
            rootNode.setChildren(projects);
        } catch (IOException e) { rootNode.setChildren(Collections.emptyList()); }

        return rootNode;
    }

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
            Files.createDirectories(masterRepoPath);

            TemplateType type = request.getTemplateType() != null ? request.getTemplateType() : TemplateType.CONSOLE;

            ProjectTemplateStrategy strategy = templateStrategies.stream()
                    .filter(s -> s.supports(type))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(type + " 템플릿 생성기를 찾을 수 없습니다."));

            log.info("🎯 템플릿 전문가 가동: {}", strategy.getClass().getSimpleName());

            strategy.generateTemplate(masterRepoPath, request.getLanguage());

            gitService.createRepository(masterRepoPath);

            if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                gitService.addRemote(masterRepoPath, request.getGitUrl());
            }

            projectRepository.save(Project.builder()
                    .name(request.getProjectName())
                    .description(request.getDescription())
                    .language(request.getLanguage())
                    .gitUrl(request.getGitUrl())
                    .workspace(workspace)
                    .build());

        } catch (Exception e) {
            log.error("프로젝트 생성 실패. 파일 롤백(삭제)을 시도합니다.", e);
            try {
                if (Files.exists(projectRoot)) {
                    FileSystemUtils.deleteRecursively(projectRoot);
                }
            } catch (IOException ioException) {
                log.error("프로젝트 폴더 롤백 실패", ioException);
            }
            throw new RuntimeException("프로젝트 생성 중 오류 발생. 롤백됨.", e);
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

        Path masterRepoPath = Paths.get(workspace.getPath(), projectName).resolve("master");
        gitService.addRemote(masterRepoPath, gitUrl);
    }

    public void createBranch(FileRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId()).orElseThrow();
        Path projectRoot = Paths.get(workspace.getPath(), request.getProjectName());

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
}