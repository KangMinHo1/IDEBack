package com.myide.backend.service;

import com.myide.backend.domain.Project;
import com.myide.backend.domain.workspace.TemplateType;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.ide.FileNode;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.dto.project.CreateProjectRequest;
import com.myide.backend.dto.project.ProjectListResponse;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import com.myide.backend.service.template.ProjectTemplateStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final String DEFAULT_BRANCH_NAME = "master";

    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final WorkspaceService workspaceService;
    private final GitService gitService;
    private final List<ProjectTemplateStrategy> templateStrategies;

    public List<ProjectListResponse> getProjectsByWorkspace(String workspaceId) {
        List<Project> projects =
                projectRepository.findByWorkspaceUuidOrderByUpdatedAtDesc(workspaceId);

        return projects.stream()
                .map(project -> ProjectListResponse.builder()
                        .id(project.getId())
                        .name(project.getName())
                        .description(project.getDescription())
                        .language(project.getLanguage())
                        .gitUrl(project.getGitUrl())
                        .updatedAt(project.getUpdatedAt())
                        .workspaceId(project.getWorkspace().getUuid())
                        .workspaceName(project.getWorkspace().getName())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public FileNode getProjectList(String workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("Workspace not found"));

        Map<String, String> dbProjectMap = workspace.getProjects().stream()
                .collect(Collectors.toMap(
                        Project::getName,
                        p -> p.getGitUrl() != null ? p.getGitUrl() : "",
                        (e, r) -> e
                ));

        Path workspaceRoot = Paths.get(workspace.getPath());

        FileNode rootNode = FileNode.builder()
                .id("root")
                .name("Projects")
                .type("folder")
                .build();

        if (!Files.exists(workspaceRoot)) {
            return rootNode;
        }

        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            List<FileNode> projects = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith(".")
                            && !path.getFileName().toString().equals("temp"))
                    /*
                     * 하드디스크에 폴더가 있어도 DB에 없는 유령 프로젝트는 무시합니다.
                     */
                    .filter(path -> dbProjectMap.containsKey(path.getFileName().toString()))
                    .map(path -> {
                        String projectName = path.getFileName().toString();

                        return FileNode.builder()
                                .id(projectName)
                                .name(projectName)
                                .type("project")
                                .gitUrl(dbProjectMap.getOrDefault(projectName, null))
                                .build();
                    })
                    .collect(Collectors.toList());

            rootNode.setChildren(projects);
        } catch (IOException e) {
            rootNode.setChildren(Collections.emptyList());
        }

        return rootNode;
    }

    @Transactional
    public void createNewProject(CreateProjectRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다."));

        Path projectRoot = Paths.get(workspace.getPath(), request.getProjectName());
        Path masterRepoPath = projectRoot.resolve(DEFAULT_BRANCH_NAME);

        if (Files.exists(projectRoot)) {
            throw new RuntimeException("이미 존재하는 프로젝트입니다.");
        }

        try {
            Files.createDirectories(masterRepoPath);

            TemplateType type = request.getTemplateType() != null
                    ? request.getTemplateType()
                    : TemplateType.CONSOLE;

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
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다."));

        Project project = projectRepository.findAll().stream()
                .filter(p -> p.getWorkspace().getUuid().equals(workspaceId)
                        && p.getName().equals(projectName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        project.setGitUrl(gitUrl);
        project.setUpdatedAt(LocalDateTime.now());
        projectRepository.save(project);

        Path masterRepoPath = Paths
                .get(workspace.getPath(), projectName, DEFAULT_BRANCH_NAME)
                .toAbsolutePath()
                .normalize();

        gitService.addRemote(masterRepoPath, gitUrl);
    }

    /*
     * Sourcetree/Git Flow 방식 브랜치 생성.
     *
     * 기존:
     * - 새 브랜치가 masterRepoPath의 HEAD 기준으로만 생성됨
     *
     * 수정 후:
     * - request.baseBranch 기준으로 새 브랜치 생성
     *
     * 예:
     * - baseBranch: develop
     * - branchName: feature/login
     *
     * 실제 worktree 폴더:
     * - feature%2Flogin
     */
    @Transactional
    public void createBranch(FileRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다."));

        String projectName = request.getProjectName();
        String branchName = workspaceService.normalizeBranchName(request.getBranchName());

        String rawBaseBranch = request.getBaseBranch();

        String baseBranch =
                rawBaseBranch == null || rawBaseBranch.trim().isEmpty()
                        ? DEFAULT_BRANCH_NAME
                        : workspaceService.normalizeBranchName(rawBaseBranch);

        Path projectRoot = Paths
                .get(workspace.getPath(), projectName)
                .toAbsolutePath()
                .normalize();

        Path masterRepoPath = workspaceService.getProjectPath(
                request.getWorkspaceId(),
                projectName,
                DEFAULT_BRANCH_NAME
        );

        Path worktreePath = workspaceService.getProjectPath(
                request.getWorkspaceId(),
                projectName,
                branchName
        );

        if (!Files.exists(projectRoot) || !Files.isDirectory(projectRoot)) {
            throw new RuntimeException("프로젝트 폴더를 찾을 수 없습니다.");
        }

        if (!Files.exists(masterRepoPath)) {
            throw new RuntimeException("메인 저장소를 찾을 수 없습니다.");
        }

        if (Files.exists(worktreePath)) {
            throw new RuntimeException("이미 존재하는 브랜치 작업 폴더입니다.");
        }

        if (gitService.branchExists(masterRepoPath, branchName)) {
            throw new RuntimeException("이미 존재하는 브랜치입니다.");
        }

        if (!gitService.branchExists(masterRepoPath, baseBranch)) {
            throw new RuntimeException("기준 브랜치를 찾을 수 없습니다: " + baseBranch);
        }

        gitService.createWorktree(
                masterRepoPath,
                worktreePath,
                branchName,
                baseBranch
        );
    }

    /*
     * 브랜치 목록 조회.
     *
     * 기존 방식:
     * - 프로젝트 폴더 하위 디렉터리 목록을 브랜치로 취급
     *
     * 문제:
     * - feature/login 같은 브랜치명이 폴더 구조와 충돌
     *
     * 수정 후:
     * - Git이 실제로 알고 있는 refs/heads 목록을 조회
     */
    public List<String> getBranchList(String workspaceId, String projectName) {
        Path masterRepoPath = workspaceService.getProjectPath(
                workspaceId,
                projectName,
                DEFAULT_BRANCH_NAME
        );

        if (!Files.exists(masterRepoPath) || !Files.isDirectory(masterRepoPath)) {
            return Collections.emptyList();
        }

        try {
            return gitService.getLocalBranches(masterRepoPath);
        } catch (Exception e) {
            log.warn("Git 브랜치 목록 조회 실패. 폴더 기반 fallback을 사용합니다: {}", e.getMessage());

            return getBranchListFromFoldersFallback(workspaceId, projectName);
        }
    }

    /*
     * Git 브랜치 목록 조회 실패 시 fallback.
     * 정상 흐름에서는 거의 사용하지 않습니다.
     */
    private List<String> getBranchListFromFoldersFallback(String workspaceId, String projectName) {
        Path projectPath = workspaceService.getProjectRootPath(workspaceId, projectName);
        List<String> branches = new ArrayList<>();

        if (Files.exists(projectPath) && Files.isDirectory(projectPath)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectPath)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)
                            && !entry.getFileName().toString().startsWith(".")) {

                        String folderName = entry.getFileName().toString();
                        branches.add(workspaceService.toBranchNameFromFolderName(folderName));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("브랜치 목록 조회 실패", e);
            }
        }

        branches.sort(String::compareToIgnoreCase);

        return branches;
    }
}