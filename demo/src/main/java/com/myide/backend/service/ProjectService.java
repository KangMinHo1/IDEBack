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

@Slf4j // 로그(기록)를 남기기 위한 마법의 어노테이션 (log.info 등을 쓸 수 있게 해줌)
@Service // 스프링에게 "이 클래스는 비즈니스 로직을 처리하는 서비스야!" 라고 알려줌
@RequiredArgsConstructor // final이 붙은 변수들을 자동으로 조립(주입)해 주는 롬복 어노테이션
public class ProjectService {

    // DB와 소통하는 저장소(Repository) 친구들
    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;

    // Git 관련된 명령(init, branch 등)을 대신해 주는 전문가
    private final GitService gitService;

    // 💡 [현업의 마법 - 전략 패턴]
    // 스프링이 켜질 때, ProjectTemplateStrategy를 구현한 모든 템플릿 전문가들
    // (SpringBoot 전문가, React 전문가 등)을 찾아서 이 리스트에 알아서 넣어줍니다!
    private final List<ProjectTemplateStrategy> templateStrategies;

    /**
     * 특정 워크스페이스(방)에 있는 프로젝트 목록을 파일 트리(폴더 구조) 형태로 만들어주는 기능
     */
    @Transactional(readOnly = true) // DB의 데이터를 읽기만 할 때 성능을 높여주는 옵션
    public FileNode getProjectList(String workspaceId) {
        // 1. 방 번호(workspaceId)로 워크스페이스를 찾습니다. 없으면 에러 던짐!
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("Workspace not found"));

        // 2. DB에 저장된 프로젝트 이름과 Git 주소를 매칭해서 지도(Map)로 만듭니다.
        Map<String, String> dbProjectMap = workspace.getProjects().stream()
                .collect(Collectors.toMap(Project::getName, p -> p.getGitUrl() != null ? p.getGitUrl() : "", (e, r) -> e));

        // 3. 실제 컴퓨터(서버) 하드디스크에 있는 워크스페이스 폴더 경로를 잡습니다.
        Path workspaceRoot = Paths.get(workspace.getPath());

        // 4. 프론트엔드에 내려줄 최상위 'Projects' 가짜 폴더 껍데기를 하나 만듭니다.
        FileNode rootNode = FileNode.builder().id("root").name("Projects").type("folder").build();

        // 5. 폴더가 아예 없으면 그냥 빈 껍데기 반환
        if (!Files.exists(workspaceRoot)) return rootNode;

        // 6. 하드디스크 폴더를 쓱 훑어보고(Stream), 숨김 파일(.으로 시작)이나 temp 폴더는 빼고 리스트를 만듭니다.
        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            List<FileNode> projects = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith(".") && !path.getFileName().toString().equals("temp"))
                    .map(path -> {
                        String pName = path.getFileName().toString();
                        // 아까 만든 DB 지도(Map)에서 Git 주소도 꺼내서 같이 포장해 줍니다.
                        return FileNode.builder().id(pName).name(pName).type("project").gitUrl(dbProjectMap.getOrDefault(pName, null)).build();
                    }).collect(Collectors.toList());
            rootNode.setChildren(projects);
        } catch (IOException e) { rootNode.setChildren(Collections.emptyList()); }

        return rootNode; // 완성된 트리를 프론트로 슝!
    }

    /**
     * 💡 [핵심] 유저가 "프로젝트 생성!" 버튼을 눌렀을 때 실행되는 엄청나게 중요한 기능
     */
    @Transactional // 중간에 에러가 나면 DB에 저장했던 걸 없던 일로 싹 되돌려주는 생명줄
    public void createNewProject(CreateProjectRequest request) {
        // 1. 방을 찾습니다.
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다."));

        // 2. 만들 프로젝트의 하드디스크 경로를 계산합니다. (예: /workspaces/방1/프로젝트A)
        Path projectRoot = Paths.get(workspace.getPath(), request.getProjectName());

        // 💡 [Git 적용] 모든 프로젝트의 기본 코드는 'master' 브랜치 폴더 안에 담깁니다!
        Path masterRepoPath = projectRoot.resolve("master");

        // 3. 이미 같은 이름의 폴더가 있으면 중복 에러!
        if (Files.exists(projectRoot)) {
            throw new RuntimeException("이미 존재하는 프로젝트입니다.");
        }

        try {
            // 4. 하드디스크에 폴더를 진짜로 만듭니다.
            Files.createDirectories(masterRepoPath);

            // 5. 유저가 어떤 템플릿(리액트? 스프링?)을 골랐는지 확인합니다. (안 골랐으면 기본 콘솔)
            TemplateType type = request.getTemplateType() != null ? request.getTemplateType() : TemplateType.CONSOLE;

            // 6. 💡 [전략 패턴의 꽃] 수많은 if-else 없이, 전문가 리스트에서 지금 필요한 전문가 딱 1명만 뽑아냅니다.
            ProjectTemplateStrategy strategy = templateStrategies.stream()
                    .filter(s -> s.supports(type)) // "너 REACT 할 줄 알아?" 물어봐서 YES 하는 애 찾기
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(type + " 템플릿 생성기를 찾을 수 없습니다."));

            log.info("🎯 템플릿 전문가 가동: {}", strategy.getClass().getSimpleName());

            // 7. 전문가에게 폴더 경로를 주면서 "여기에 파일 좀 쫙 깔아줘!" 하고 명령합니다.
            strategy.generateTemplate(masterRepoPath, request.getLanguage());

            // 8. 💡 [Git 적용] 파일이 다 깔렸으니, 이 폴더를 Git 저장소로 만듭니다 (git init)
            gitService.createRepository(masterRepoPath);

            // 9. 만약 깃허브 주소도 입력했다면 원격 연결까지 해줍니다 (git remote add origin)
            if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                gitService.addRemote(masterRepoPath, request.getGitUrl());
            }

            // 10. 모든 게 완벽하게 성공했으면 마지막으로 DB에 "이런 프로젝트 생김!" 하고 도장을 찍습니다.
            projectRepository.save(Project.builder()
                    .name(request.getProjectName())
                    .description(request.getDescription())
                    .language(request.getLanguage())
                    .gitUrl(request.getGitUrl())
                    .workspace(workspace)
                    .build());

        } catch (Exception e) {
            log.error("프로젝트 생성 실패. 파일 롤백(삭제)을 시도합니다.", e);
            // 💡 [에러 처리] DB는 @Transactional이 되돌려주지만, 이미 만들어진 폴더는 직접 지워야 합니다!
            try {
                if (Files.exists(projectRoot)) {
                    FileSystemUtils.deleteRecursively(projectRoot); // 만들다 만 찌꺼기 폴더 통째로 날리기
                }
            } catch (IOException ioException) {
                log.error("프로젝트 폴더 롤백 실패", ioException);
            }
            throw new RuntimeException("프로젝트 생성 중 오류 발생. 롤백됨.", e);
        }
    }

    /**
     * 프로젝트 깃허브 연동 주소 변경 기능
     */
    @Transactional
    public void updateProjectGitUrl(String workspaceId, String projectName, String gitUrl) {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow();
        Project project = projectRepository.findAll().stream()
                .filter(p -> p.getWorkspace().getUuid().equals(workspaceId) && p.getName().equals(projectName))
                .findFirst().orElseThrow();

        // DB 정보 수정
        project.setGitUrl(gitUrl);
        project.setUpdatedAt(LocalDateTime.now());
        projectRepository.save(project);

        // 💡 [Git 적용] 실제 하드디스크의 master 폴더에 깃허브 리모트 정보를 업데이트합니다.
        Path masterRepoPath = Paths.get(workspace.getPath(), projectName).resolve("master");
        gitService.addRemote(masterRepoPath, gitUrl);
    }

    /**
     * 💡 [Git 샌드박스 핵심 기능]
     * master 브랜치의 코드를 복사해서, 나만의 격리된 방(focus-...)을 만들어주는 기능입니다.
     */
    public void createBranch(FileRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId()).orElseThrow();
        Path projectRoot = Paths.get(workspace.getPath(), request.getProjectName());

        Path masterRepoPath = projectRoot.resolve("master"); // 복사할 원본
        Path worktreePath = projectRoot.resolve(request.getBranchName()); // 새로 만들어질 방

        if (!Files.exists(masterRepoPath)) throw new RuntimeException("메인 저장소를 찾을 수 없습니다.");
        if (Files.exists(worktreePath)) throw new RuntimeException("이미 존재하는 브랜치입니다.");

        // Git Worktree 기술을 써서 0.1초 만에 코드를 싹 복사해 줍니다.
        gitService.createWorktree(masterRepoPath, worktreePath, request.getBranchName());
    }

    /**
     * 현재 프로젝트에 있는 브랜치(방) 목록을 쫙 가져옵니다.
     */
    public List<String> getBranchList(String workspaceId, String projectName) {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow();
        Path projectPath = Paths.get(workspace.getPath(), projectName);
        List<String> branches = new ArrayList<>();

        if (Files.exists(projectPath) && Files.isDirectory(projectPath)) {
            // 프로젝트 폴더 안에 있는 하위 폴더 이름들(master, focus-aaa 등)이 곧 브랜치 이름입니다!
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