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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
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

    /* =========================================================
       프로젝트 목록
    ========================================================= */

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

    /* =========================================================
       IDE 프로젝트 트리
    ========================================================= */

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
                    .filter(path ->
                            !path.getFileName().toString().startsWith(".")
                                    && !path.getFileName().toString().equals("temp")
                    )
                    /*
                     * 하드디스크에 폴더가 있어도
                     * DB에 없는 유령 프로젝트는 무시합니다.
                     */
                    .filter(path ->
                            dbProjectMap.containsKey(
                                    path.getFileName().toString()
                            )
                    )
                    .map(path -> {
                        String projectName =
                                path.getFileName().toString();

                        return FileNode.builder()
                                .id(projectName)
                                .name(projectName)
                                .type("project")
                                .gitUrl(
                                        dbProjectMap.getOrDefault(
                                                projectName,
                                                null
                                        )
                                )
                                .build();
                    })
                    .collect(Collectors.toList());

            rootNode.setChildren(projects);

        } catch (IOException e) {
            rootNode.setChildren(Collections.emptyList());
        }

        return rootNode;
    }

    /* =========================================================
       프로젝트 생성
    ========================================================= */

    @Transactional
    public void createNewProject(CreateProjectRequest request) {
        Workspace workspace = workspaceRepository.findById(
                        request.getWorkspaceId()
                )
                .orElseThrow(() ->
                        new RuntimeException(
                                "워크스페이스를 찾을 수 없습니다."
                        )
                );

        Path projectRoot =
                Paths.get(
                        workspace.getPath(),
                        request.getProjectName()
                );

        Path masterRepoPath =
                projectRoot.resolve(DEFAULT_BRANCH_NAME);

        if (Files.exists(projectRoot)) {
            throw new RuntimeException(
                    "이미 존재하는 프로젝트입니다."
            );
        }

        try {
            Files.createDirectories(masterRepoPath);

            TemplateType type =
                    request.getTemplateType() != null
                            ? request.getTemplateType()
                            : TemplateType.CONSOLE;

            ProjectTemplateStrategy strategy =
                    templateStrategies.stream()
                            .filter(s -> s.supports(type))
                            .findFirst()
                            .orElseThrow(() ->
                                    new RuntimeException(
                                            type
                                                    + " 템플릿 생성기를 찾을 수 없습니다."
                                    )
                            );

            log.info(
                    "🎯 템플릿 전문가 가동: {}",
                    strategy.getClass().getSimpleName()
            );

            strategy.generateTemplate(
                    masterRepoPath,
                    request.getLanguage()
            );

            gitService.createRepository(masterRepoPath);

            if (request.getGitUrl() != null
                    && !request.getGitUrl().isBlank()) {

                gitService.addRemote(
                        masterRepoPath,
                        request.getGitUrl()
                );
            }

            projectRepository.save(
                    Project.builder()
                            .name(request.getProjectName())
                            .description(
                                    request.getDescription()
                            )
                            .language(
                                    request.getLanguage()
                            )
                            .gitUrl(
                                    request.getGitUrl()
                            )
                            .workspace(workspace)
                            .build()
            );

        } catch (Exception e) {
            log.error(
                    "프로젝트 생성 실패. 파일 롤백(삭제)을 시도합니다.",
                    e
            );

            try {
                if (Files.exists(projectRoot)) {
                    FileSystemUtils.deleteRecursively(
                            projectRoot
                    );
                }
            } catch (IOException ioException) {
                log.error(
                        "프로젝트 폴더 롤백 실패",
                        ioException
                );
            }

            throw new RuntimeException(
                    "프로젝트 생성 중 오류 발생. 롤백됨.",
                    e
            );
        }
    }

    /* =========================================================
       프로젝트 삭제
    ========================================================= */

    @Transactional
    public void deleteProject(
            Long projectId,
            Long userId
    ) {
        Project project =
                projectRepository.findById(projectId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "작업 폴더를 찾을 수 없습니다."
                                )
                        );

        Workspace workspace =
                project.getWorkspace();

        /*
         * 작업 폴더 삭제 권한
         *
         * 개인 프로젝트:
         * - OWNER만 삭제 가능
         *
         * 팀 프로젝트:
         * - OWNER만 삭제 가능
         * - MEMBER 삭제 불가
         */
        if (workspace.getOwner() == null
                || workspace.getOwner().getId() == null
                || !workspace.getOwner().getId().equals(userId)) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "프로젝트 OWNER만 작업 폴더를 삭제할 수 있습니다."
            );
        }

        /*
         * 실제 폴더 경로
         *
         * 예:
         * C:\WebIDE\workspaces\WAIVS
         * └─ frontend
         *    ├─ master
         *    └─ feature%2Flogin
         */
        Path workspaceRoot =
                Paths.get(workspace.getPath())
                        .toAbsolutePath()
                        .normalize();

        Path projectRoot =
                workspaceRoot
                        .resolve(project.getName())
                        .toAbsolutePath()
                        .normalize();

        /*
         * 혹시 Project 이름이 비정상적인 경우
         * Workspace 바깥 경로가 삭제되지 않도록 보호합니다.
         */
        if (!projectRoot.startsWith(workspaceRoot)
                || projectRoot.equals(workspaceRoot)) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "유효하지 않은 작업 폴더 경로입니다."
            );
        }

        String projectName =
                project.getName();

        log.info(
                "[작업 폴더 삭제] 시작 - projectId={}, name={}, path={}",
                projectId,
                projectName,
                projectRoot
        );

        /*
         * 현재 DB 구조에서는 project.id를 FK로
         * 직접 참조하는 테이블이 없으므로
         * Project 엔티티만 삭제하면 됩니다.
         */
        projectRepository.delete(project);

        /*
         * DB 오류가 있으면 이 시점에서 바로 발생시키도록 flush
         */
        projectRepository.flush();

        /*
         * 실제 디렉터리는 DB COMMIT 성공 후 제거합니다.
         *
         * DB 롤백됐는데 폴더만 먼저 사라지는 상황을 방지합니다.
         */
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCommit() {
                        log.info(
                                "[작업 폴더 삭제] DB COMMIT 성공 - 실제 폴더 삭제 시작: {}",
                                projectRoot
                        );

                        deleteProjectDirectorySafely(
                                projectRoot
                        );
                    }
                }
        );
    }

    /* =========================================================
       실제 작업 폴더 안전 삭제
    ========================================================= */

    private void deleteProjectDirectorySafely(
            Path projectRoot
    ) {
        Path normalizedPath =
                projectRoot
                        .toAbsolutePath()
                        .normalize();

        if (!Files.exists(normalizedPath)) {
            log.info(
                    "[작업 폴더 삭제] 실제 폴더 없음 - 삭제 생략: {}",
                    normalizedPath
            );

            return;
        }

        final int maxAttempts = 5;

        for (int attempt = 1;
             attempt <= maxAttempts;
             attempt++) {

            try {
                log.info(
                        "[작업 폴더 삭제] 실제 폴더 삭제 시도 {}/{}: {}",
                        attempt,
                        maxAttempts,
                        normalizedPath
                );

                forceDeleteDirectory(
                        normalizedPath
                );

                if (!Files.exists(normalizedPath)) {
                    log.info(
                            "[작업 폴더 삭제] 실제 폴더 삭제 완료: {}",
                            normalizedPath
                    );

                    return;
                }

            } catch (Exception e) {
                log.warn(
                        "[작업 폴더 삭제] 삭제 실패 {}/{} - {}: {}",
                        attempt,
                        maxAttempts,
                        e.getClass().getSimpleName(),
                        e.getMessage()
                );
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    log.warn(
                            "[작업 폴더 삭제] 재시도 대기 중 인터럽트 발생"
                    );

                    return;
                }
            }
        }

        if (Files.exists(normalizedPath)) {
            log.error(
                    "[작업 폴더 삭제] 최종 실패 - 폴더가 아직 존재합니다: {}",
                    normalizedPath
            );
        }
    }

    /* =========================================================
       Windows / Git 폴더 강제 삭제
    ========================================================= */

    private void forceDeleteDirectory(
            Path root
    ) throws IOException {

        if (!Files.exists(root)) {
            return;
        }

        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attrs
                    ) throws IOException {

                        clearWindowsReadOnly(file);

                        try {
                            Files.deleteIfExists(file);

                        } catch (IOException firstException) {

                            /*
                             * .git/objects 등의 readonly 문제를 대비해
                             * 다시 권한 해제 후 한 번 더 시도합니다.
                             */
                            clearWindowsReadOnly(file);

                            try {
                                Thread.sleep(100L);
                            } catch (InterruptedException e) {
                                Thread.currentThread()
                                        .interrupt();
                            }

                            Files.deleteIfExists(file);
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(
                            Path file,
                            IOException exc
                    ) throws IOException {

                        log.warn(
                                "[작업 폴더 삭제] 파일 접근 실패 - 재시도: {} / {}",
                                file,
                                exc != null
                                        ? exc.getMessage()
                                        : "unknown"
                        );

                        clearWindowsReadOnly(file);

                        try {
                            Files.deleteIfExists(file);

                            return FileVisitResult.CONTINUE;

                        } catch (IOException deleteException) {
                            log.error(
                                    "[작업 폴더 삭제] 파일 삭제 최종 실패: {}",
                                    file,
                                    deleteException
                            );

                            throw deleteException;
                        }
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(
                            Path dir,
                            IOException exc
                    ) throws IOException {

                        if (exc != null) {
                            throw exc;
                        }

                        clearWindowsReadOnly(dir);

                        Files.deleteIfExists(dir);

                        return FileVisitResult.CONTINUE;
                    }
                }
        );
    }

    /* =========================================================
       Windows readonly 해제
    ========================================================= */

    private void clearWindowsReadOnly(
            Path path
    ) {
        try {
            DosFileAttributeView dosView =
                    Files.getFileAttributeView(
                            path,
                            DosFileAttributeView.class
                    );

            if (dosView != null) {
                try {
                    dosView.setReadOnly(false);
                } catch (Exception ignored) {
                }
            }

            try {
                path.toFile()
                        .setWritable(
                                true,
                                false
                        );
            } catch (Exception ignored) {
            }

        } catch (Exception ignored) {
        }
    }

    /* =========================================================
       Git URL 수정
    ========================================================= */

    @Transactional
    public void updateProjectGitUrl(
            String workspaceId,
            String projectName,
            String gitUrl
    ) {
        Workspace workspace =
                workspaceRepository.findById(workspaceId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "워크스페이스를 찾을 수 없습니다."
                                )
                        );

        Project project =
                projectRepository.findAll()
                        .stream()
                        .filter(p ->
                                p.getWorkspace()
                                        .getUuid()
                                        .equals(workspaceId)
                                        && p.getName()
                                        .equals(projectName)
                        )
                        .findFirst()
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "프로젝트를 찾을 수 없습니다."
                                )
                        );

        project.setGitUrl(gitUrl);
        project.setUpdatedAt(
                LocalDateTime.now()
        );

        projectRepository.save(project);

        Path masterRepoPath =
                Paths.get(
                                workspace.getPath(),
                                projectName,
                                DEFAULT_BRANCH_NAME
                        )
                        .toAbsolutePath()
                        .normalize();

        gitService.addRemote(
                masterRepoPath,
                gitUrl
        );
    }

    /* =========================================================
       브랜치 생성
    ========================================================= */

    @Transactional
    public void createBranch(
            FileRequest request
    ) {
        Workspace workspace =
                workspaceRepository.findById(
                                request.getWorkspaceId()
                        )
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "워크스페이스를 찾을 수 없습니다."
                                )
                        );

        String projectName =
                request.getProjectName();

        String branchName =
                workspaceService.normalizeBranchName(
                        request.getBranchName()
                );

        String rawBaseBranch =
                request.getBaseBranch();

        String baseBranch =
                rawBaseBranch == null
                        || rawBaseBranch
                        .trim()
                        .isEmpty()
                        ? DEFAULT_BRANCH_NAME
                        : workspaceService.normalizeBranchName(
                        rawBaseBranch
                );

        Path projectRoot =
                Paths.get(
                                workspace.getPath(),
                                projectName
                        )
                        .toAbsolutePath()
                        .normalize();

        Path masterRepoPath =
                workspaceService.getProjectPath(
                        request.getWorkspaceId(),
                        projectName,
                        DEFAULT_BRANCH_NAME
                );

        Path worktreePath =
                workspaceService.getProjectPath(
                        request.getWorkspaceId(),
                        projectName,
                        branchName
                );

        if (!Files.exists(projectRoot)
                || !Files.isDirectory(projectRoot)) {

            throw new RuntimeException(
                    "프로젝트 폴더를 찾을 수 없습니다."
            );
        }

        if (!Files.exists(masterRepoPath)) {
            throw new RuntimeException(
                    "메인 저장소를 찾을 수 없습니다."
            );
        }

        if (Files.exists(worktreePath)) {
            throw new RuntimeException(
                    "이미 존재하는 브랜치 작업 폴더입니다."
            );
        }

        if (gitService.branchExists(
                masterRepoPath,
                branchName
        )) {
            throw new RuntimeException(
                    "이미 존재하는 브랜치입니다."
            );
        }

        if (!gitService.branchExists(
                masterRepoPath,
                baseBranch
        )) {
            throw new RuntimeException(
                    "기준 브랜치를 찾을 수 없습니다: "
                            + baseBranch
            );
        }

        gitService.createWorktree(
                masterRepoPath,
                worktreePath,
                branchName,
                baseBranch
        );
    }

    /* =========================================================
       브랜치 목록
    ========================================================= */

    public List<String> getBranchList(
            String workspaceId,
            String projectName
    ) {
        Path masterRepoPath =
                workspaceService.getProjectPath(
                        workspaceId,
                        projectName,
                        DEFAULT_BRANCH_NAME
                );

        if (!Files.exists(masterRepoPath)
                || !Files.isDirectory(masterRepoPath)) {

            return Collections.emptyList();
        }

        try {
            return gitService.getLocalBranches(
                    masterRepoPath
            );

        } catch (Exception e) {
            log.warn(
                    "Git 브랜치 목록 조회 실패. 폴더 기반 fallback을 사용합니다: {}",
                    e.getMessage()
            );

            return getBranchListFromFoldersFallback(
                    workspaceId,
                    projectName
            );
        }
    }

    /* =========================================================
       브랜치 목록 fallback
    ========================================================= */

    private List<String> getBranchListFromFoldersFallback(
            String workspaceId,
            String projectName
    ) {
        Path projectPath =
                workspaceService.getProjectRootPath(
                        workspaceId,
                        projectName
                );

        List<String> branches =
                new ArrayList<>();

        if (Files.exists(projectPath)
                && Files.isDirectory(projectPath)) {

            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(
                                 projectPath
                         )) {

                for (Path entry : stream) {
                    if (Files.isDirectory(entry)
                            && !entry.getFileName()
                            .toString()
                            .startsWith(".")) {

                        String folderName =
                                entry.getFileName()
                                        .toString();

                        branches.add(
                                workspaceService
                                        .toBranchNameFromFolderName(
                                                folderName
                                        )
                        );
                    }
                }

            } catch (IOException e) {
                throw new RuntimeException(
                        "브랜치 목록 조회 실패",
                        e
                );
            }
        }

        branches.sort(
                String::compareToIgnoreCase
        );

        return branches;
    }
}