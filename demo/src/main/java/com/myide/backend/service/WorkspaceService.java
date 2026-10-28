package com.myide.backend.service;

import com.myide.backend.domain.Project;
import com.myide.backend.domain.User;
import com.myide.backend.domain.devlog.Devlog;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import com.myide.backend.domain.workspace.WorkspaceType;

import com.myide.backend.dto.workspace.InviteMemberRequest;
import com.myide.backend.dto.workspace.WorkspaceCreateRequest;
import com.myide.backend.dto.workspace.WorkspaceDuplicateRequest;
import com.myide.backend.dto.workspace.WorkspaceInvitationResponse;
import com.myide.backend.dto.workspace.WorkspaceListResponse;
import com.myide.backend.dto.workspace.WorkspaceMemberResponse;
import com.myide.backend.dto.workspace.WorkspaceUpdateRequest;

import com.myide.backend.repository.DevlogRepository;
import com.myide.backend.repository.NotificationRepository;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.ScheduleRepository;
import com.myide.backend.repository.UserRepository;

import com.myide.backend.repository.design.DesignApiSpecRepository;
import com.myide.backend.repository.design.DesignDocCheckpointRepository;
import com.myide.backend.repository.design.DesignDocSnapshotRepository;
import com.myide.backend.repository.design.DesignDocumentRepository;
import com.myide.backend.repository.design.DesignRequirementRepository;

import com.myide.backend.repository.rearrange.VirtualFileTreeRepository;
import com.myide.backend.repository.workspace.WorkspaceMemberRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;

import com.myide.backend.service.saferun.RunAssembler;
import com.myide.backend.service.saferun.SafeRunPaths;
import com.myide.backend.service.workspace.GitWorktreeRelinker;
import com.myide.backend.service.workspace.WorkspaceDataCloner;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;

import java.net.URLDecoder;
import java.net.URLEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;

import java.time.LocalDateTime;

import java.util.Comparator;
import java.util.List;

import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    /*
     * 새 워크스페이스가 놓일 위치는 UserSpaceService 가 사용자별 개인 폴더 안에서 정한다
     * (createWorkspace 참고). DEFAULT_ROOT 는 duplicateWorkspace 가 원본에 부모 폴더가
     * 없을 때만 쓰는 대비용이라 남겨 둔다. 예전에 공용 루트 아래 만든 워크스페이스는
     * DB 에 실제 경로가 남아 있어 그대로 열린다.
     */
    private static final String DEFAULT_ROOT =
            "C:\\WebIDE\\workspaces";

    private static final String DEFAULT_BRANCH_NAME =
            "master";


    /*
     * =====================================================
     * Repository
     * =====================================================
     */

    private final WorkspaceRepository workspaceRepository;

    private final WorkspaceMemberRepository workspaceMemberRepository;

    private final UserRepository userRepository;

    private final ProjectRepository projectRepository;

    private final ScheduleRepository scheduleRepository;

    private final DevlogRepository devlogRepository;

    private final NotificationRepository notificationRepository;


    /*
     * 설계 관련 Repository
     */
    private final DesignDocumentRepository designDocumentRepository;

    private final DesignDocSnapshotRepository designDocSnapshotRepository;

    private final DesignDocCheckpointRepository designDocCheckpointRepository;

    private final DesignApiSpecRepository designApiSpecRepository;

    private final DesignRequirementRepository designRequirementRepository;


    /*
     * 프로젝트 복제 때 팀원·일정·개발일지·설계 등 작업 데이터를 옮긴다.
     */
    private final WorkspaceDataCloner workspaceDataCloner;

    private final VirtualFileTreeRepository virtualFileTreeRepository;


    /*
     * 사용자별 개인 폴더
     *
     * 프론트는 화면에 보이는 가상 경로("C:" 로 시작)만 주고받는다. 진짜 경로로 바꾸고
     * 개인 폴더 밖인지 가려내는 일은 이 서비스가 맡는다. 누구의 폴더인지는 요청 본문이
     * 아니라 토큰에서 읽는다(CurrentUserService).
     */
    private final UserSpaceService userSpaceService;

    private final CurrentUserService currentUserService;


    /*
     * 안전 실행·샌드박스 폴더의 루트. 워크스페이스를 지울 때 그 워크스페이스 몫을
     * 함께 지우는 데만 쓴다. 경로 규칙의 정본은 SafeRunPaths 다.
     */
    @Value("${ide.safe-run-root}")
    private String safeRunRoot;


    /*
     * =====================================================
     * 브랜치 이름 처리
     * =====================================================
     */

    public String normalizeBranchName(
            String branchName
    ) {

        if (
                branchName == null ||
                        branchName.isBlank()
        ) {
            return DEFAULT_BRANCH_NAME;
        }

        String normalized =
                branchName.trim();

        if ("main-repo".equals(normalized)) {
            return DEFAULT_BRANCH_NAME;
        }

        return normalized;
    }


    public String toBranchFolderName(
            String branchName
    ) {

        String normalizedBranchName =
                normalizeBranchName(
                        branchName
                );

        if (
                DEFAULT_BRANCH_NAME.equals(
                        normalizedBranchName
                )
        ) {
            return DEFAULT_BRANCH_NAME;
        }

        return URLEncoder
                .encode(
                        normalizedBranchName,
                        StandardCharsets.UTF_8
                )
                .replace(
                        "+",
                        "%20"
                );
    }


    public String toBranchNameFromFolderName(
            String folderName
    ) {

        if (
                folderName == null ||
                        folderName.isBlank()
        ) {
            return DEFAULT_BRANCH_NAME;
        }

        if (
                DEFAULT_BRANCH_NAME.equals(
                        folderName
                )
        ) {
            return DEFAULT_BRANCH_NAME;
        }

        return URLDecoder.decode(
                folderName,
                StandardCharsets.UTF_8
        );
    }


    /*
     * =====================================================
     * 경로 조회
     * =====================================================
     */

    public Path getWorkspaceRootPath(
            String workspaceId
    ) {

        Workspace workspace =
                workspaceRepository
                        .findById(
                                workspaceId
                        )
                        .orElseThrow(
                                () -> new RuntimeException(
                                        "워크스페이스를 찾을 수 없습니다: "
                                                + workspaceId
                                )
                        );

        return Paths
                .get(
                        workspace.getPath()
                )
                .toAbsolutePath();
    }


    public Path getProjectRootPath(
            String workspaceId,
            String projectName
    ) {

        Workspace workspace =
                workspaceRepository
                        .findById(
                                workspaceId
                        )
                        .orElseThrow(
                                () -> new RuntimeException(
                                        "워크스페이스를 찾을 수 없습니다: "
                                                + workspaceId
                                )
                        );

        return Paths
                .get(
                        workspace.getPath(),
                        projectName
                )
                .toAbsolutePath();
    }


    public Path getProjectPath(
            String workspaceId,
            String projectName,
            String branchName
    ) {

        Workspace workspace =
                workspaceRepository
                        .findById(
                                workspaceId
                        )
                        .orElseThrow(
                                () -> new RuntimeException(
                                        "워크스페이스를 찾을 수 없습니다: "
                                                + workspaceId
                                )
                        );

        String branchFolderName =
                toBranchFolderName(
                        branchName
                );

        return Paths
                .get(
                        workspace.getPath(),
                        projectName,
                        branchFolderName
                )
                .toAbsolutePath()
                .normalize();
    }

    /*
     * 이 경로 아래에 워크스페이스가 하나라도 있는지.
     *
     * 폴더 선택 창에서 폴더를 지울 때 쓴다. 폴더만 지우면 DB 에는 워크스페이스가 남고
     * 디스크만 사라져서, 목록에는 보이는데 열면 깨지는 유령이 된다. 워크스페이스는
     * deleteWorkspace 로 지워야 DB 행과 설계 데이터(FK)까지 함께 정리된다.
     */
    public boolean hasWorkspaceUnder(Path realPath, Long userId) {
        Path target = realPath.toAbsolutePath().normalize();

        return workspaceRepository.findMyAllWorkspaces(userId).stream()
                .map(workspace -> Paths.get(workspace.getPath()).toAbsolutePath().normalize())
                .anyMatch(workspacePath -> workspacePath.startsWith(target));
    }


    /*
     * =====================================================
     * Workspace 생성
     * =====================================================
     */

    @Transactional
    public Workspace createWorkspace(
            WorkspaceCreateRequest request
    ) {

        /*
         * 워크스페이스 이름이 곧 폴더 이름이 된다. 이름에 / 나 .. 가 들어오면
         * 폴더가 여러 겹으로 쪼개지거나 개인 폴더 밖으로 나갈 수 있어 먼저 막는다.
         */
        userSpaceService.validateFolderName(
                request.getName()
        );

        /*
         * 프론트가 보내는 path 는 화면에 보이는 가상 경로("C:" 로 시작)다. 진짜 경로로
         * 바꾸면서 개인 폴더 밖인지도 함께 걸러진다.
         *
         * 이 부분을 예전처럼 request.getPath() 를 그대로 쓰는 코드로 되돌리면, 컴파일은
         * 되지만 개인 폴더 기능이 조용히 꺼진다. 가상 경로를 진짜로 믿고 서버 드라이브
         * 맨 위에 폴더를 만들게 된다. (pull 병합 때 한 번 그렇게 될 뻔했다.)
         *
         * 누구의 폴더인지는 요청 본문의 userId 가 아니라 토큰에서 정한다. 본문 값을
         * 믿으면 남의 userId 를 적어 그 사람 폴더에 만들 수 있기 때문이다.
         */
        Long currentUserId =
                currentUserService.getCurrentUserId();

        Path base =
                userSpaceService.toReal(
                        request.getPath(),
                        currentUserId
                );

        Path rootPath =
                base
                        .resolve(
                                request.getName().trim()
                        )
                        .normalize();

        /* 이어 붙인 뒤에도 개인 폴더 안에 남아 있는지 마지막으로 확인한다. */
        userSpaceService.toVirtual(
                rootPath,
                currentUserId
        );


        if (
                Files.exists(
                        rootPath
                )
        ) {

            throw new RuntimeException(
                    "이미 존재하는 워크스페이스 경로입니다."
            );
        }


        try {

            Files.createDirectories(
                    rootPath
            );

            String absolutePath =
                    rootPath
                            .toAbsolutePath()
                            .toString();


            Long ownerLongId;

            try {

                ownerLongId =
                        Long.valueOf(
                                request.getUserId()
                        );

            } catch (NumberFormatException e) {

                throw new IllegalArgumentException(
                        "워크스페이스 생성 실패: 올바른 숫자형 회원 ID가 필요합니다."
                );
            }


            User ownerUser =
                    userRepository
                            .findById(
                                    ownerLongId
                            )
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "존재하지 않는 회원입니다."
                                            )
                            );


            Workspace newWorkspace;


            /*
             * 팀 Workspace
             */
            if (
                    request.getType()
                            == WorkspaceType.TEAM
            ) {

                newWorkspace =
                        Workspace.createTeam(
                                ownerUser,
                                request.getName(),
                                request.getDescription(),
                                absolutePath
                        );


                Workspace savedWorkspace =
                        workspaceRepository.save(
                                newWorkspace
                        );


                WorkspaceMember owner =
                        WorkspaceMember.createOwner(
                                savedWorkspace,
                                ownerUser
                        );


                workspaceMemberRepository.save(
                        owner
                );


                return savedWorkspace;
            }


            /*
             * 개인 Workspace
             */
            newWorkspace =
                    Workspace.createPersonal(
                            ownerUser,
                            request.getName(),
                            request.getDescription(),
                            absolutePath
                    );


            return workspaceRepository.save(
                    newWorkspace
            );


        } catch (Exception e) {

            /*
             * Workspace 생성 실패 시
             * 생성된 실제 폴더 롤백
             */

            try {

                if (
                        Files.exists(
                                rootPath
                        )
                ) {

                    FileSystemUtils.deleteRecursively(
                            rootPath
                    );
                }

            } catch (IOException ioException) {

                System.err.println(
                        "디렉토리 롤백 실패: "
                                + ioException.getMessage()
                );
            }


            throw new RuntimeException(
                    "워크스페이스 생성 중 오류 발생.",
                    e
            );
        }
    }


    /*
     * =====================================================
     * Workspace 수정
     *
     * OWNER만 가능
     * =====================================================
     */

    @Transactional
    public void updateWorkspace(
            String workspaceId,
            Long userId,
            WorkspaceUpdateRequest request
    ) {

        Workspace workspace =
                getOwnedWorkspace(
                        workspaceId,
                        userId
                );


        String newName =
                normalizeWorkspaceName(
                        request.getName()
                );


        workspace.setName(
                newName
        );


        workspace.setDescription(
                normalizeDescription(
                        request.getDescription()
                )
        );


        workspace.setUpdatedAt(
                LocalDateTime.now()
        );
    }


    /*
     * =====================================================
     * Workspace 복제
     *
     * 실제 폴더 + Workspace + Project
     * + 팀원(ACCEPTED) · 일정 · 개발일지 · 설계 · 가상 파일 트리
     *
     * DB 쪽 작업 데이터는 WorkspaceDataCloner 가, 복사한 폴더의
     * git worktree 연결은 GitWorktreeRelinker 가 사본 쪽으로 다시 잇는다.
     *
     * 실패 처리
     *  - DB: 같은 트랜잭션이라 통째로 롤백된다.
     *  - 폴더: 트랜잭션이 커밋되지 않고 끝나면(메서드 안 예외든 커밋 실패든)
     *          afterCompletion 에서 사본 폴더를 지운다. 사본 폴더는 이 메서드가
     *          직접 새로 만든 경우에만 지운다(원본·남의 폴더는 건드리지 않는다).
     * =====================================================
     */

    @Transactional
    public void duplicateWorkspace(
            String workspaceId,
            Long userId,
            WorkspaceDuplicateRequest request
    ) {

        Workspace original =
                getOwnedWorkspace(
                        workspaceId,
                        userId
                );


        String duplicateName =
                normalizeWorkspaceName(
                        request.getName()
                );


        String duplicateDescription =
                normalizeDescription(
                        request.getDescription()
                );


        Path originalRoot =
                Paths
                        .get(
                                original.getPath()
                        )
                        .toAbsolutePath()
                        .normalize();


        if (
                !Files.exists(
                        originalRoot
                )
        ) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "원본 프로젝트 폴더를 찾을 수 없습니다."
            );
        }


        Path parent =
                originalRoot.getParent();


        if (parent == null) {

            parent =
                    Paths
                            .get(
                                    DEFAULT_ROOT
                            )
                            .toAbsolutePath()
                            .normalize();
        }


        Path duplicateRoot =
                parent
                        .resolve(
                                duplicateName
                        )
                        .toAbsolutePath()
                        .normalize();


        if (
                Files.exists(
                        duplicateRoot
                )
        ) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "같은 이름의 프로젝트 폴더가 이미 존재합니다."
            );
        }


        /*
         * 사본 폴더를 "새로 만들기"로 먼저 잡는다.
         *
         * 위 exists 검사와 복사 사이에 같은 이름으로 동시에 복제하면, 두 요청이 한
         * 폴더에 섞여 복사되고 실패한 쪽이 다른 쪽 폴더까지 지울 수 있다.
         * createDirectory 는 이미 있으면 실패하므로, 성공한 요청만 이 폴더의 주인이 된다.
         */
        try {

            Files.createDirectory(
                    duplicateRoot
            );

        } catch (FileAlreadyExistsException e) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "같은 이름의 프로젝트 폴더가 이미 존재합니다."
            );

        } catch (IOException e) {

            throw new RuntimeException(
                    "복제 폴더를 만들지 못했습니다.",
                    e
            );
        }


        boolean cleanupRegistered =
                registerDuplicateCleanup(
                        originalRoot,
                        duplicateRoot
                );


        try {

            /*
             * 실제 폴더 복제
             */
            copyDirectory(
                    originalRoot,
                    duplicateRoot
            );


            /*
             * 복사한 브랜치 폴더(worktree)가 원본 저장소를 가리키지 않도록
             * git 연결을 사본 쪽으로 다시 잇는다.
             */
            GitWorktreeRelinker.Result relinked =
                    GitWorktreeRelinker.relink(
                            originalRoot,
                            duplicateRoot
                    );

            System.out.println(
                    "[Workspace 복제] git worktree 연결 재설정: "
                            + relinked.rewritten()
                            + "건 (원본 밖 연결 "
                            + relinked.external()
                            + "건)"
            );


            User owner =
                    original.getOwner();


            Workspace duplicatedWorkspace;


            if (
                    original.getType()
                            == WorkspaceType.TEAM
            ) {

                duplicatedWorkspace =
                        Workspace.createTeam(
                                owner,
                                duplicateName,
                                duplicateDescription,
                                duplicateRoot.toString()
                        );

            } else {

                duplicatedWorkspace =
                        Workspace.createPersonal(
                                owner,
                                duplicateName,
                                duplicateDescription,
                                duplicateRoot.toString()
                        );
            }


            Workspace savedWorkspace =
                    workspaceRepository.save(
                            duplicatedWorkspace
                    );


            /*
             * 하위 Project 복제
             */
            List<Project> originalProjects =
                    projectRepository
                            .findByWorkspaceUuidOrderByUpdatedAtDesc(
                                    original.getUuid()
                            );


            List<Project> copiedProjects =
                    originalProjects
                            .stream()
                            .map(
                                    originalProject ->

                                            Project.builder()

                                                    .name(
                                                            originalProject.getName()
                                                    )

                                                    .description(
                                                            originalProject.getDescription()
                                                    )

                                                    .language(
                                                            originalProject.getLanguage()
                                                    )

                                                    .gitUrl(
                                                            originalProject.getGitUrl()
                                                    )

                                                    .workspace(
                                                            savedWorkspace
                                                    )

                                                    .updatedAt(
                                                            LocalDateTime.now()
                                                    )

                                                    .build()
                            )
                            .collect(
                                    Collectors.toList()
                            );


            if (
                    !copiedProjects.isEmpty()
            ) {

                projectRepository.saveAll(
                        copiedProjects
                );
            }


            /*
             * 팀원 · 일정 · 개발일지 · 설계 · 가상 파일 트리
             * (TEAM 의 OWNER 멤버 행도 여기서 만든다)
             */
            workspaceDataCloner.cloneAll(
                    original,
                    savedWorkspace
            );


            savedWorkspace.setUpdatedAt(
                    LocalDateTime.now()
            );


            /*
             * 제약 위반이 커밋 시점이 아니라 여기서 드러나게 한다.
             * (커밋에서 터져도 afterCompletion 이 폴더를 치우지만, 원인이
             *  이 메서드의 예외로 나오는 편이 응답 메시지가 정확하다.)
             */
            workspaceRepository.flush();


        } catch (Exception e) {

            /*
             * 트랜잭션 동기화가 없으면(afterCompletion 이 안 불리면) 여기서 바로 치운다.
             * 있으면 롤백이 끝난 뒤 afterCompletion 이 치운다.
             */
            if (!cleanupRegistered) {

                deleteFailedDuplicate(
                        originalRoot,
                        duplicateRoot
                );
            }


            if (
                    e instanceof ResponseStatusException
                            responseStatusException
            ) {

                throw responseStatusException;
            }


            throw new RuntimeException(
                    "프로젝트 복제 중 오류가 발생했습니다.",
                    e
            );
        }
    }


    /*
     * 복제 트랜잭션이 커밋되지 않고 끝나면 사본 폴더를 지운다.
     *
     * 메서드 안의 예외뿐 아니라 커밋 자체가 실패한 경우(flush 이후의 제약 위반,
     * DB 연결 끊김 등)도 잡으려면 catch 가 아니라 트랜잭션 완료 시점에 봐야 한다.
     *
     * @return 등록했으면 true. 트랜잭션 동기화가 없는 호출이면 false(호출자가 직접 치운다).
     */
    private boolean registerDuplicateCleanup(
            Path originalRoot,
            Path duplicateRoot
    ) {

        if (
                !TransactionSynchronizationManager
                        .isSynchronizationActive()
        ) {
            return false;
        }


        TransactionSynchronizationManager
                .registerSynchronization(

                        new TransactionSynchronization() {

                            @Override
                            public void afterCompletion(int status) {

                                if (status == STATUS_COMMITTED) {
                                    return;
                                }

                                System.out.println(
                                        "[Workspace 복제] DB 커밋 실패/롤백 - 복제 폴더 정리: "
                                                + duplicateRoot
                                );

                                deleteFailedDuplicate(
                                        originalRoot,
                                        duplicateRoot
                                );
                            }
                        }
                );

        return true;
    }


    /*
     * 실패한 복제본 폴더 삭제.
     *
     * 원본과 같은 경로이거나 서로 포함 관계면 절대 지우지 않는다. 복제본 경로는
     * 이름 검사를 거친 "원본의 형제 폴더"라 그럴 일이 없어야 하지만, 지우는 쪽이
     * 원본을 날릴 수 있는 유일한 지점이라 한 번 더 막는다.
     */
    private void deleteFailedDuplicate(
            Path originalRoot,
            Path duplicateRoot
    ) {

        Path original =
                originalRoot
                        .toAbsolutePath()
                        .normalize();

        Path duplicate =
                duplicateRoot
                        .toAbsolutePath()
                        .normalize();


        if (
                original.startsWith(duplicate) ||
                        duplicate.startsWith(original)
        ) {

            System.err.println(
                    "[Workspace 복제] 원본과 겹치는 경로라 정리를 건너뜁니다: "
                            + duplicate
            );

            return;
        }


        deleteWorkspaceDirectorySafely(
                duplicate
        );
    }


    /*
     * =====================================================
     * Workspace 삭제
     *
     * 현재 DB에서 확인된 workspace FK를
     * 전부 먼저 제거한 후 Workspace 삭제
     * =====================================================
     */

    @Transactional
    public void deleteWorkspace(
            String workspaceId,
            Long userId
    ) {

        /*
         * OWNER 권한 확인
         */
        Workspace workspace =
                getOwnedWorkspace(
                        workspaceId,
                        userId
                );


        Path workspaceRoot =
                Paths
                        .get(
                                workspace.getPath()
                        )
                        .toAbsolutePath()
                        .normalize();


        /*
         * =================================================
         * 1. 설계 체크포인트
         * =================================================
         */

        designDocCheckpointRepository
                .deleteAllByWorkspaceUuid(
                        workspaceId
                );


        /*
         * =================================================
         * 2. 설계 Snapshot
         * =================================================
         */

        designDocSnapshotRepository
                .deleteAllByWorkspaceUuid(
                        workspaceId
                );


        /*
         * =================================================
         * 3. API 설계 명세
         * =================================================
         */

        designApiSpecRepository
                .deleteAllByWorkspaceUuid(
                        workspaceId
                );


        /*
         * =================================================
         * 4. 요구사항
         * =================================================
         */

        designRequirementRepository
                .deleteAllByWorkspaceUuid(
                        workspaceId
                );


        /*
         * =================================================
         * 5. 구 버전 DesignDocument
         * =================================================
         */

        designDocumentRepository
                .deleteAllByWorkspaceUuid(
                        workspaceId
                );


        /*
         * =================================================
         * 6. Workspace 관련 알림
         * =================================================
         */

        notificationRepository
                .deleteAllByWorkspaceUuid(
                        workspaceId
                );


        /*
         * 가상 파일 트리(보기용 뷰)
         *
         * FK 없이 workspaceId 문자열만 들고 있어 지우지 않으면 주인 없는 행으로 남는다.
         * 복제가 이 행들을 함께 옮기므로, 복제본을 지울 때도 같이 치운다.
         */
        virtualFileTreeRepository.deleteAll(
                virtualFileTreeRepository
                        .findByWorkspaceIdOrderByIdAsc(
                                workspaceId
                        )
        );


        /*
         * =================================================
         * 7. Devlog
         *
         * Devlog가 Schedule FK를 가지고 있으므로
         * Schedule보다 먼저 삭제해야 함.
         * =================================================
         */

        List<Devlog> devlogs =
                devlogRepository
                        .findByWorkspace_UuidOrderByWorkedDateDescCreatedAtDesc(
                                workspaceId
                        );


        if (
                !devlogs.isEmpty()
        ) {

            devlogRepository.deleteAll(
                    devlogs
            );

            devlogRepository.flush();
        }


        /*
         * =================================================
         * 8. Schedule
         * =================================================
         */

        List<Schedule> schedules =
                scheduleRepository
                        .findByWorkspace_UuidOrderByStartDateAscCreatedAtDesc(
                                workspaceId
                        );


        if (
                !schedules.isEmpty()
        ) {

            scheduleRepository.deleteAll(
                    schedules
            );

            scheduleRepository.flush();
        }


        /*
         * =================================================
         * 9. WorkspaceMember
         * =================================================
         */

        workspaceMemberRepository
                .deleteAllByWorkspaceId(
                        workspaceId
                );


        workspaceMemberRepository.flush();


        /*
         * =================================================
         * 10. Workspace 삭제
         *
         * Project는 Workspace의 cascade 설정으로 함께 삭제됨.
         * =================================================
         */

        workspaceRepository.delete(
                workspace
        );


        /*
         * SQL을 지금 실행하여
         * FK 오류가 있으면 Commit 전에 확인.
         */
        workspaceRepository.flush();


        /*
         * =================================================
         * 11. DB Commit 성공 후 실제 Workspace 폴더 삭제
         * =================================================
         */

        if (
                TransactionSynchronizationManager
                        .isSynchronizationActive()
        ) {

            TransactionSynchronizationManager
                    .registerSynchronization(

                            new TransactionSynchronization() {

                                @Override
                                public void afterCommit() {

                                    System.out.println(
                                            "[Workspace 삭제] DB COMMIT 성공 - 실제 폴더 삭제 실행"
                                    );

                                    deleteWorkspaceDirectorySafely(
                                            workspaceRoot
                                    );

                                    deleteSafeRunDirectorySafely(
                                            workspaceId
                                    );
                                }
                            }
                    );

        } else {

            deleteWorkspaceDirectorySafely(
                    workspaceRoot
            );

            deleteSafeRunDirectorySafely(
                    workspaceId
            );
        }
    }


    /*
     * 안전 실행·샌드박스 폴더(green / sandbox)는 워크스페이스 폴더 밖에 따로 있다.
     * 워크스페이스를 지울 때 같이 지우지 않으면 주인 없는 폴더가 디스크에 계속 남는다.
     *
     * 실패해도 워크스페이스 삭제 자체는 이미 끝났으므로 예외를 밖으로 내지 않는다.
     */
    private void deleteSafeRunDirectorySafely(
            String workspaceId
    ) {

        try {
            RunAssembler.deleteRecursively(
                    SafeRunPaths.workspaceRoot(
                            safeRunRoot,
                            workspaceId
                    )
            );
        } catch (Exception e) {
            System.out.println(
                    "[Workspace 삭제] 안전 실행 폴더 삭제 실패: "
                            + e.getMessage()
            );
        }
    }


    /*
     * =====================================================
     * TEAM Workspace 나가기
     *
     * MEMBER만 가능
     * =====================================================
     */

    @Transactional
    public void leaveWorkspace(
            String workspaceId,
            Long userId
    ) {

        Workspace workspace =
                workspaceRepository
                        .findByUuid(
                                workspaceId
                        )
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "프로젝트를 찾을 수 없습니다."
                                        )
                        );


        if (
                workspace.getType()
                        != WorkspaceType.TEAM
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "개인 프로젝트에서는 나가기 기능을 사용할 수 없습니다."
            );
        }


        /*
         * OWNER는 프로젝트 나가기 불가
         */
        if (
                workspace.getOwner() != null &&
                        workspace.getOwner().getId() != null &&
                        workspace
                                .getOwner()
                                .getId()
                                .equals(
                                        userId
                                )
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "프로젝트 OWNER는 프로젝트를 나갈 수 없습니다."
            );
        }


        WorkspaceMember member =
                workspaceMemberRepository
                        .findByWorkspace_UuidAndUser_Id(
                                workspaceId,
                                userId
                        )
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "프로젝트 멤버 정보를 찾을 수 없습니다."
                                        )
                        );


        if (
                member.getStatus()
                        != WorkspaceMember.JoinStatus.ACCEPTED
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "참여 중인 프로젝트가 아닙니다."
            );
        }


        workspaceMemberRepository.delete(
                member
        );
    }


    /*
     * =====================================================
     * 팀원 초대
     * =====================================================
     */

    @Transactional
    public void inviteMember(
            InviteMemberRequest request
    ) {

        Workspace workspace =
                workspaceRepository
                        .findById(
                                request.getWorkspaceId()
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "존재하지 않는 워크스페이스입니다."
                                        )
                        );


        User user =
                userRepository
                        .findByEmail(
                                request.getEmail()
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "해당 이메일로 가입된 유저가 없습니다."
                                        )
                        );


        if (
                workspaceMemberRepository
                        .existsByWorkspaceAndUser(
                                workspace,
                                user
                        )
        ) {

            throw new IllegalStateException(
                    "이미 초대되었거나 가입된 멤버입니다."
            );
        }


        WorkspaceMember member =
                WorkspaceMember
                        .builder()
                        .workspace(
                                workspace
                        )
                        .user(
                                user
                        )
                        .role(
                                WorkspaceMember
                                        .WorkspaceRole
                                        .MEMBER
                        )
                        .status(
                                WorkspaceMember
                                        .JoinStatus
                                        .PENDING
                        )
                        .build();


        workspaceMemberRepository.save(
                member
        );
    }


    /*
     * =====================================================
     * 초대 수락
     * =====================================================
     */

    @Transactional
    public void acceptInvitation(
            String workspaceId,
            Long userId
    ) {

        WorkspaceMember member =
                workspaceMemberRepository
                        .findByWorkspace_UuidAndUser_Id(
                                workspaceId,
                                userId
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "초대 내역이 존재하지 않습니다."
                                        )
                        );


        if (
                member.getStatus()
                        == WorkspaceMember.JoinStatus.ACCEPTED
        ) {

            throw new IllegalStateException(
                    "이미 가입이 완료된 워크스페이스입니다."
            );
        }


        member.acceptInvitation();
    }


    /*
     * =====================================================
     * 초대 거절
     * =====================================================
     */

    @Transactional
    public void rejectInvitation(
            String workspaceId,
            Long userId
    ) {

        WorkspaceMember member =
                workspaceMemberRepository
                        .findByWorkspace_UuidAndUser_Id(
                                workspaceId,
                                userId
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "초대 내역이 존재하지 않습니다."
                                        )
                        );


        workspaceMemberRepository.delete(
                member
        );
    }


    /*
     * =====================================================
     * 대기 중인 초대
     * =====================================================
     */

    @Transactional(readOnly = true)
    public List<WorkspaceInvitationResponse>
    getPendingInvitations(
            Long userId
    ) {

        List<WorkspaceMember> pendingMembers =
                workspaceMemberRepository
                        .findByUser_IdAndStatus(
                                userId,
                                WorkspaceMember.JoinStatus.PENDING
                        );


        return pendingMembers
                .stream()
                .map(
                        member ->

                                WorkspaceInvitationResponse
                                        .builder()

                                        .workspaceId(
                                                member
                                                        .getWorkspace()
                                                        .getUuid()
                                        )

                                        .workspaceName(
                                                member
                                                        .getWorkspace()
                                                        .getName()
                                        )

                                        .build()
                )
                .collect(
                        Collectors.toList()
                );
    }


    /*
     * =====================================================
     * Workspace 멤버
     * =====================================================
     */

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse>
    getWorkspaceMembers(
            String workspaceId
    ) {

        List<WorkspaceMember> members =
                workspaceMemberRepository
                        .findByWorkspace_UuidAndStatus(
                                workspaceId,
                                WorkspaceMember.JoinStatus.ACCEPTED
                        );


        return members
                .stream()
                .map(
                        member ->

                                WorkspaceMemberResponse
                                        .builder()

                                        .userId(
                                                member
                                                        .getUser()
                                                        .getId()
                                        )

                                        .email(
                                                member
                                                        .getUser()
                                                        .getEmail()
                                        )

                                        .nickname(
                                                member
                                                        .getUser()
                                                        .getNickname()
                                        )

                                        .role(
                                                member
                                                        .getRole()
                                                        .name()
                                        )

                                        .build()
                )
                .collect(
                        Collectors.toList()
                );
    }


    /*
     * =====================================================
     * 내 Workspace
     * =====================================================
     */

    @Transactional(readOnly = true)
    public List<WorkspaceListResponse>
    getMyWorkspaces(
            Long userId
    ) {

        return workspaceRepository
                .findMyAllWorkspaces(
                        userId
                )
                .stream()

                .sorted(
                        Comparator.comparing(
                                Workspace::getUpdatedAt,

                                Comparator.nullsLast(
                                        Comparator.reverseOrder()
                                )
                        )
                )

                .map(
                        workspace ->
                                WorkspaceListResponse.from(
                                        workspace,
                                        userId
                                )
                )

                .collect(
                        Collectors.toList()
                );
    }


    /*
     * =====================================================
     * OWNER 검사
     * =====================================================
     */

    private Workspace getOwnedWorkspace(
            String workspaceId,
            Long userId
    ) {

        Workspace workspace =
                workspaceRepository
                        .findByUuid(
                                workspaceId
                        )
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "프로젝트를 찾을 수 없습니다."
                                        )
                        );


        if (
                workspace.getOwner() == null ||
                        workspace.getOwner().getId() == null ||
                        !workspace
                                .getOwner()
                                .getId()
                                .equals(
                                        userId
                                )
        ) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "프로젝트 OWNER만 이 작업을 수행할 수 있습니다."
            );
        }


        return workspace;
    }


    /*
     * =====================================================
     * Workspace 이름 검사
     * =====================================================
     */

    private String normalizeWorkspaceName(
            String name
    ) {

        if (
                name == null ||
                        name.isBlank()
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "프로젝트 이름을 입력해주세요."
            );
        }


        String normalized =
                name.trim();


        if (
                normalized.matches(
                        ".*[<>:\"/\\\\|?*].*"
                )
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "프로젝트 이름에 사용할 수 없는 문자가 포함되어 있습니다."
            );
        }


        if (
                ".".equals(
                        normalized
                ) ||
                        "..".equals(
                                normalized
                        )
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "사용할 수 없는 프로젝트 이름입니다."
            );
        }


        return normalized;
    }


    /*
     * =====================================================
     * 설명
     * =====================================================
     */

    private String normalizeDescription(
            String description
    ) {

        if (
                description == null
        ) {
            return "";
        }


        return description.trim();
    }


    /*
     * =====================================================
     * 디렉터리 복제
     * =====================================================
     */

    private void copyDirectory(
            Path sourceRoot,
            Path targetRoot
    ) throws IOException {

        Files.createDirectories(
                targetRoot
        );


        try (
                Stream<Path> paths =
                        Files.walk(
                                sourceRoot
                        )
        ) {

            paths.forEach(
                    source -> {

                        Path relative =
                                sourceRoot.relativize(
                                        source
                                );


                        Path target =
                                targetRoot.resolve(
                                        relative
                                );


                        try {

                            if (
                                    Files.isDirectory(
                                            source
                                    )
                            ) {

                                Files.createDirectories(
                                        target
                                );

                            } else {

                                Files.copy(
                                        source,
                                        target,
                                        StandardCopyOption.REPLACE_EXISTING
                                );
                            }

                        } catch (IOException e) {

                            throw new UncheckedIOException(
                                    e
                            );
                        }
                    }
            );


        } catch (UncheckedIOException e) {

            throw e.getCause();
        }
    }


    /*
     * =====================================================
     * 실제 Workspace 폴더 삭제
     *
     * DB Commit 이후 실행
     *
     * Windows / Git .git 폴더 대응
     * =====================================================
     */

    private void deleteWorkspaceDirectorySafely(
            Path workspaceRoot
    ) {

        if (workspaceRoot == null) {

            System.err.println(
                    "[Workspace 삭제] workspaceRoot가 null입니다."
            );

            return;
        }


        Path normalizedPath =
                workspaceRoot
                        .toAbsolutePath()
                        .normalize();


        System.out.println(
                "[Workspace 삭제] 실제 폴더 삭제 시작: "
                        + normalizedPath
        );


        /*
         * 이미 없는 폴더라면 종료
         */
        if (
                !Files.exists(
                        normalizedPath
                )
        ) {

            System.out.println(
                    "[Workspace 삭제] 이미 존재하지 않는 폴더입니다: "
                            + normalizedPath
            );

            return;
        }


        /*
         * Windows에서 Git 관련 파일 핸들이
         * 잠깐 남아있을 수 있으므로 최대 5회 재시도
         */
        final int maxAttempts = 5;


        for (
                int attempt = 1;
                attempt <= maxAttempts;
                attempt++
        ) {

            try {

                forceDeleteDirectory(
                        normalizedPath
                );


                /*
                 * 실제로 폴더가 없어졌는지 확인
                 */
                if (
                        !Files.exists(
                                normalizedPath
                        )
                ) {

                    System.out.println(
                            "[Workspace 삭제] 실제 폴더 삭제 성공: "
                                    + normalizedPath
                    );

                    return;
                }


                System.err.println(
                        "[Workspace 삭제] 삭제 후에도 폴더가 남아있습니다. "
                                + attempt
                                + "/"
                                + maxAttempts
                );


            } catch (Exception e) {

                System.err.println(
                        "[Workspace 삭제] 폴더 삭제 실패 "
                                + attempt
                                + "/"
                                + maxAttempts
                );

                System.err.println(
                        "[Workspace 삭제] 경로: "
                                + normalizedPath
                );

                System.err.println(
                        "[Workspace 삭제] 예외 타입: "
                                + e.getClass().getName()
                );

                System.err.println(
                        "[Workspace 삭제] 메시지: "
                                + e.getMessage()
                );
            }


            /*
             * 다음 재시도 전에
             * Windows 파일 핸들이 풀릴 시간을 줌
             */
            if (
                    attempt < maxAttempts
            ) {

                try {

                    Thread.sleep(
                            1000L
                    );

                } catch (InterruptedException e) {

                    Thread.currentThread()
                            .interrupt();

                    System.err.println(
                            "[Workspace 삭제] 재시도 대기 중 인터럽트 발생"
                    );

                    return;
                }
            }
        }


        /*
         * 모든 재시도 이후 최종 검사
         */
        if (
                Files.exists(
                        normalizedPath
                )
        ) {

            System.err.println(
                    "[Workspace 삭제] 최종 실패 - 폴더가 아직 존재합니다: "
                            + normalizedPath
            );

        } else {

            System.out.println(
                    "[Workspace 삭제] 실제 폴더 삭제 완료: "
                            + normalizedPath
            );
        }
    }


    /*
     * =====================================================
     * Workspace 폴더 강제 재귀 삭제
     *
     * 안쪽 파일부터 삭제하고
     * 마지막에 상위 폴더를 제거한다.
     *
     * .git 폴더도 동일하게 처리.
     * =====================================================
     */

    private void forceDeleteDirectory(
            Path root
    ) throws IOException {

        if (
                root == null ||
                        !Files.exists(
                                root
                        )
        ) {

            return;
        }


        Files.walkFileTree(

                root,

                new SimpleFileVisitor<Path>() {


                    /*
                     * =========================================
                     * 파일 삭제
                     * =========================================
                     */

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attrs
                    ) throws IOException {

                        /*
                         * Windows 읽기 전용 해제
                         */
                        clearWindowsReadOnly(
                                file
                        );


                        try {

                            Files.deleteIfExists(
                                    file
                            );

                        } catch (IOException firstException) {

                            /*
                             * 첫 삭제가 실패했다면
                             * 속성을 다시 풀고 잠시 대기 후 재시도
                             */

                            clearWindowsReadOnly(
                                    file
                            );


                            try {

                                Thread.sleep(
                                        100L
                                );

                            } catch (InterruptedException e) {

                                Thread.currentThread()
                                        .interrupt();

                                throw new IOException(
                                        "파일 삭제 재시도 대기 중 인터럽트 발생: "
                                                + file,
                                        e
                                );
                            }


                            Files.deleteIfExists(
                                    file
                            );
                        }


                        return FileVisitResult.CONTINUE;
                    }


                    /*
                     * =========================================
                     * 파일 접근 자체가 실패한 경우
                     * =========================================
                     */

                    @Override
                    public FileVisitResult visitFileFailed(
                            Path file,
                            IOException exc
                    ) throws IOException {

                        clearWindowsReadOnly(
                                file
                        );


                        try {

                            Files.deleteIfExists(
                                    file
                            );

                            return FileVisitResult.CONTINUE;

                        } catch (IOException deleteException) {

                            System.err.println(
                                    "[Workspace 삭제] 파일 삭제 실패: "
                                            + file
                            );

                            System.err.println(
                                    "[Workspace 삭제] 예외 타입: "
                                            + deleteException
                                            .getClass()
                                            .getName()
                            );

                            System.err.println(
                                    "[Workspace 삭제] 메시지: "
                                            + deleteException
                                            .getMessage()
                            );


                            throw deleteException;
                        }
                    }


                    /*
                     * =========================================
                     * 내부 파일이 모두 지워진 뒤
                     * 디렉터리 삭제
                     * =========================================
                     */

                    @Override
                    public FileVisitResult postVisitDirectory(
                            Path dir,
                            IOException exc
                    ) throws IOException {

                        if (
                                exc != null
                        ) {

                            throw exc;
                        }


                        clearWindowsReadOnly(
                                dir
                        );


                        Files.deleteIfExists(
                                dir
                        );


                        return FileVisitResult.CONTINUE;
                    }
                }
        );
    }


    /*
     * =====================================================
     * Windows 파일/폴더 읽기 전용 속성 제거
     *
     * Git .git 내부 파일 대응
     * =====================================================
     */

    private void clearWindowsReadOnly(
            Path path
    ) {

        if (
                path == null
        ) {
            return;
        }


        /*
         * Windows DOS 파일 속성 처리
         */
        try {

            DosFileAttributeView dosView =
                    Files.getFileAttributeView(
                            path,
                            DosFileAttributeView.class
                    );


            if (
                    dosView != null
            ) {

                dosView.setReadOnly(
                        false
                );
            }

        } catch (Exception ignored) {

            /*
             * 이미 삭제된 파일이거나
             * DOS 속성을 지원하지 않는 경우 무시
             */
        }


        /*
         * 일반 writable 속성도 같이 설정
         */
        try {

            path
                    .toFile()
                    .setWritable(
                            true,
                            false
                    );

        } catch (Exception ignored) {

            /*
             * 삭제 진행을 막지 않음
             */
        }
    }
}