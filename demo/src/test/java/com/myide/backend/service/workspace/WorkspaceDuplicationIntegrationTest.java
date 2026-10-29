package com.myide.backend.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.config.QuerydslConfig;
import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.User;
import com.myide.backend.domain.design.DesignApiSpec;
import com.myide.backend.domain.design.DesignDocCheckpoint;
import com.myide.backend.domain.design.DesignDocSnapshot;
import com.myide.backend.domain.design.DesignRequirement;
import com.myide.backend.domain.devlog.Devlog;
import com.myide.backend.domain.notification.Notification;
import com.myide.backend.domain.notification.NotificationType;
import com.myide.backend.domain.rearrange.VirtualFileTree;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.schedule.ScheduleStatus;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import com.myide.backend.dto.design.DesignRequirementRequest;
import com.myide.backend.dto.design.v2.DesignDocWriteRequest;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.workspace.WorkspaceDuplicateRequest;
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
import com.myide.backend.security.WorkspaceAccessGuard;
import com.myide.backend.service.CurrentUserService;
import com.myide.backend.service.DesignService;
import com.myide.backend.service.UserSpaceService;
import com.myide.backend.service.WorkspaceService;
import com.myide.backend.service.design.DesignModelCodec;
import com.myide.backend.service.design.DesignSnapshotService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 프로젝트 복제가 DB 작업 데이터까지 독립된 사본으로 만드는지 확인한다.
 *
 * 실제 운영 DB 를 건드리지 않도록 H2(MySQL 모드) 메모리 DB 와 임시 폴더만 쓴다.
 * 엔티티에서 스키마를 만들므로, 여기서는 design_requirements / design_api_specs 의
 * uuid 가 (workspace_uuid, uuid) 범위로만 유일한 "마이그레이션 후" 상태다.
 *
 * 설계 저장은 실제 DesignSnapshotService 로 시드·저장해, 복제본의 자동 저장이
 * 원본 행과 부딪히지 않는지까지 본다.
 */
@DataJpaTest
@Import({
        QuerydslConfig.class,
        WorkspaceService.class,
        WorkspaceDataCloner.class,
        DesignSnapshotService.class,
        DesignService.class,
        WorkspaceAccessGuard.class,
        DesignModelCodec.class,
        WorkspaceDuplicationIntegrationTest.JacksonConfig.class
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dup-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "ide.safe-run-root=${java.io.tmpdir}/dup-test-safe-run"
})
class WorkspaceDuplicationIntegrationTest {

    @TestConfiguration
    static class JacksonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @MockBean
    UserSpaceService userSpaceService;

    @MockBean
    CurrentUserService currentUserService;

    @Autowired WorkspaceService workspaceService;
    @Autowired DesignSnapshotService snapshotService;
    @Autowired DesignService designService;
    @Autowired DesignModelCodec codec;
    @Autowired ObjectMapper objectMapper;

    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository memberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired DevlogRepository devlogRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired DesignDocSnapshotRepository snapshotRepository;
    @Autowired DesignDocCheckpointRepository checkpointRepository;
    @Autowired DesignDocumentRepository documentRepository;
    @Autowired DesignRequirementRepository requirementRepository;
    @Autowired DesignApiSpecRepository apiSpecRepository;
    @Autowired VirtualFileTreeRepository treeRepository;
    @Autowired EntityManager em;

    @TempDir
    Path temp;

    private User owner;
    private User member;
    private User invitee;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(user("owner-" + suffix));
        member = userRepository.save(user("member-" + suffix));
        invitee = userRepository.save(user("invitee-" + suffix));
    }

    // ── 시나리오 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("개인 프로젝트: 폴더·프로젝트·일정·개발일지·설계·가상 트리가 새 id 로 복제되고 원본은 그대로다")
    void personalDuplicate() throws Exception {
        Workspace original = createOriginal(false);
        Map<String, Long> before = counts(original.getUuid());

        Workspace copy = duplicate(original, "복제본");

        // 기본 정보
        assertThat(copy.getUuid()).isNotEqualTo(original.getUuid());
        assertThat(copy.getType()).isEqualTo(original.getType());
        assertThat(copy.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(copy.getDescription()).isEqualTo("복제 설명");
        assertThat(projectRepository.findByWorkspaceUuidOrderByUpdatedAtDesc(copy.getUuid()))
                .extracting(Project::getName, Project::getLanguage, Project::getGitUrl)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("app", LanguageType.JAVA, "https://example.com/r.git"));

        // 코드 파일
        Path copyRoot = Path.of(copy.getPath());
        assertThat(copyRoot.getParent()).isEqualTo(Path.of(original.getPath()).getParent());
        assertThat(Files.readString(copyRoot.resolve("app/master/Main.java"))).isEqualTo("class Main {}\n");

        // git worktree 연결이 사본을 가리킨다
        assertThat(Files.readString(copyRoot.resolve("app/dev/.git")).replace('\\', '/'))
                .contains(copyRoot.toString().replace('\\', '/') + "/app/master/.git/worktrees/dev");

        // 일정: 상태·완료일·담당자 유지, id·uuid 는 새로
        List<Schedule> origSchedules = schedules(original);
        List<Schedule> copySchedules = schedules(copy);
        assertThat(copySchedules).hasSize(2);
        for (int i = 0; i < 2; i++) {
            Schedule o = origSchedules.get(i);
            Schedule c = copySchedules.get(i);
            assertThat(c.getId()).isNotEqualTo(o.getId());
            assertThat(c.getUuid()).isNotEqualTo(o.getUuid());
            assertThat(c.getTitle()).isEqualTo(o.getTitle());
            assertThat(c.getStatus()).isEqualTo(o.getStatus());
            assertThat(c.getCompletedAt()).isEqualTo(o.getCompletedAt());
            assertThat(c.getStartDate()).isEqualTo(o.getStartDate());
            assertThat(c.getAssignee().getId()).isEqualTo(o.getAssignee().getId());
            assertThat(c.getCreatedBy().getId()).isEqualTo(o.getCreatedBy().getId());
            assertThat(c.getCreatedAt()).isEqualTo(o.getCreatedAt());
        }
        assertThat(copySchedules.get(1).getStatus()).isEqualTo(ScheduleStatus.DONE);
        assertThat(copySchedules.get(1).getCompletedAt()).isNotNull();

        // 개발일지: 일반·연결 모두, 태그 유지, 연결은 "복제된" 일정
        List<Devlog> copyDevlogs = devlogRepository.findByWorkspace_UuidOrderByWorkedDateDescCreatedAtDesc(copy.getUuid());
        assertThat(copyDevlogs).hasSize(2);
        Devlog general = byTitle(copyDevlogs, "일반 일지");
        Devlog linked = byTitle(copyDevlogs, "연결 일지");
        assertThat(general.getSchedule()).isNull();
        assertThat(general.getTags()).containsExactly("spring", "jpa");
        assertThat(linked.getTags()).containsExactly("done");
        assertThat(linked.getSchedule().getWorkspace().getUuid()).isEqualTo(copy.getUuid());
        assertThat(linked.getSchedule().getTitle()).isEqualTo("배포");
        assertThat(linked.getSchedule().getId())
                .isNotEqualTo(byTitle(devlogRepository.findByWorkspace_UuidOrderByWorkedDateDescCreatedAtDesc(original.getUuid()), "연결 일지").getSchedule().getId());

        // 설계: 저장본(Yjs 그대로)·체크포인트(순서)·역투영 행
        DesignDocSnapshot origSnap = snapshotRepository.findByWorkspace_Uuid(original.getUuid()).orElseThrow();
        DesignDocSnapshot copySnap = snapshotRepository.findByWorkspace_Uuid(copy.getUuid()).orElseThrow();
        assertThat(copySnap.getUuid()).isNotEqualTo(origSnap.getUuid());
        assertThat(copySnap.getYjsUpdateBase64()).isEqualTo(origSnap.getYjsUpdateBase64());
        assertThat(copySnap.getProjectionJson()).isEqualTo(origSnap.getProjectionJson());

        assertThat(snapshotService.listCheckpoints(copy.getUuid(), owner.getId()))
                .extracting(cp -> cp.label())
                .containsExactlyElementsOf(snapshotService.listCheckpoints(original.getUuid(), owner.getId())
                        .stream().map(cp -> cp.label()).toList());
        assertThat(snapshotService.getDoc(copy.getUuid(), owner.getId()).yjsUpdate())
                .isEqualTo(origSnap.getYjsUpdateBase64());

        assertThat(documentRepository.findByWorkspace_Uuid(copy.getUuid())).isPresent();
        assertThat(requirementRepository.findByWorkspace_UuidOrderByCreatedAtAsc(copy.getUuid()))
                .extracting(DesignRequirement::getUuid)
                .containsExactly("req_aaaaaaaa");
        assertThat(apiSpecRepository.findByWorkspace_UuidOrderByCreatedAtAsc(copy.getUuid()))
                .extracting(DesignApiSpec::getUuid)
                .containsExactly("api_cccccccc");

        // 자료실이 쓰는 옛 엔드포인트도 사본 데이터를 돌려준다
        assertThat(designService.getRequirements(copy.getUuid(), owner.getId())).hasSize(1);
        assertThat(designService.getApiSpecs(copy.getUuid(), owner.getId())).hasSize(1);

        // 가상 트리는 옮기고, 알림은 옮기지 않는다
        assertThat(treeRepository.findByWorkspaceIdOrderByIdAsc(copy.getUuid()))
                .extracting(VirtualFileTree::getViewName, VirtualFileTree::isActive)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("언어별", true));
        assertThat(notificationsOf(copy)).isZero();

        // 원본은 한 건도 바뀌지 않았다
        assertThat(counts(original.getUuid())).isEqualTo(before);
    }

    @Test
    @DisplayName("팀 프로젝트: OWNER 와 수락한 팀원은 같은 권한으로, 초대 대기는 옮기지 않는다")
    void teamDuplicate() throws Exception {
        Workspace original = createOriginal(true);
        List<WorkspaceMember> originalMembersBefore = memberRepository.findAll().stream()
                .filter(m -> m.getWorkspace().getUuid().equals(original.getUuid())).toList();

        Workspace copy = duplicate(original, "팀 복제본");

        Map<Long, WorkspaceMember> copyMembers = memberRepository.findAll().stream()
                .filter(m -> m.getWorkspace().getUuid().equals(copy.getUuid()))
                .collect(Collectors.toMap(m -> m.getUser().getId(), Function.identity()));

        assertThat(copyMembers).containsOnlyKeys(owner.getId(), member.getId());
        assertThat(copyMembers.get(owner.getId()).getRole()).isEqualTo(WorkspaceMember.WorkspaceRole.OWNER);
        assertThat(copyMembers.get(member.getId()).getRole()).isEqualTo(WorkspaceMember.WorkspaceRole.MEMBER);
        assertThat(copyMembers.values()).allMatch(m -> m.getStatus() == WorkspaceMember.JoinStatus.ACCEPTED);

        // 수락한 팀원은 사본 일정에 접근할 수 있고(담당자 참조 유효), 초대 대기자는 못 한다
        assertThat(workspaceRepository.findMyAllWorkspaces(member.getId()))
                .extracting(Workspace::getUuid).contains(copy.getUuid());
        assertThat(workspaceRepository.findMyAllWorkspaces(invitee.getId()))
                .extracting(Workspace::getUuid).doesNotContain(copy.getUuid());
        assertThat(schedules(copy)).anyMatch(s -> s.getAssignee().getId().equals(member.getId()));

        // 원본 멤버십은 그대로
        assertThat(memberRepository.findAll().stream()
                .filter(m -> m.getWorkspace().getUuid().equals(original.getUuid()))
                .map(m -> m.getUser().getId() + ":" + m.getRole() + ":" + m.getStatus())
                .collect(Collectors.toSet()))
                .isEqualTo(originalMembersBefore.stream()
                        .map(m -> m.getUser().getId() + ":" + m.getRole() + ":" + m.getStatus())
                        .collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("복제본의 설계 자동 저장이 원본과 충돌하지 않고, 서로의 행을 바꾸지 않는다")
    void duplicateDesignAutosave() throws Exception {
        Workspace original = createOriginal(true);
        Workspace copy = duplicate(original, "설계 복제본");

        // 복제본에서 요구사항 이름을 바꿔 자동 저장(역투영 포함)
        DesignModelV2 edited = codec.fromJson(readProjection().replace("\"name\": \"로그인\",", "\"name\": \"사본 로그인\","));
        snapshotService.save(copy.getUuid(), member.getId(), new DesignDocWriteRequest(2, "AAAAAAAAAAAAAAAACOPY", edited));
        em.flush();

        assertThat(requirementRepository.findByUuidAndWorkspace_Uuid("req_aaaaaaaa", copy.getUuid()).orElseThrow().getName())
                .isEqualTo("사본 로그인");
        assertThat(requirementRepository.findByUuidAndWorkspace_Uuid("req_aaaaaaaa", original.getUuid()).orElseThrow().getName())
                .isEqualTo("로그인 v2");

        // 원본도 계속 저장된다
        snapshotService.save(original.getUuid(), owner.getId(),
                new DesignDocWriteRequest(2, "AAAAAAAAAAAAAAAAORIG", codec.fromJson(readProjection())));
        em.flush();

        assertThat(requirementRepository.findAllByUuid("req_aaaaaaaa"))
                .extracting(r -> r.getWorkspace().getUuid() + "=" + r.getName())
                .containsExactlyInAnyOrder(original.getUuid() + "=로그인", copy.getUuid() + "=사본 로그인");
        assertThat(snapshotRepository.findByWorkspace_Uuid(copy.getUuid()).orElseThrow().getYjsUpdateBase64())
                .isEqualTo("AAAAAAAAAAAAAAAACOPY");
        assertThat(snapshotRepository.findByWorkspace_Uuid(original.getUuid()).orElseThrow().getYjsUpdateBase64())
                .isEqualTo("AAAAAAAAAAAAAAAAORIG");
    }

    @Test
    @DisplayName("id 로 고치는 옛 설계 API: 워크스페이스 경로는 해당 사본만, 모호한 옛 경로는 409")
    void legacyDesignApiIsWorkspaceScoped() throws Exception {
        Workspace original = createOriginal(false);
        Workspace copy = duplicate(original, "API 복제본");

        designService.updateRequirement(copy.getUuid(), "req_aaaaaaaa", owner.getId(),
                new DesignRequirementRequest("회원", "사본에서 수정", "설명"));
        em.flush();

        assertThat(requirementRepository.findByUuidAndWorkspace_Uuid("req_aaaaaaaa", copy.getUuid()).orElseThrow().getName())
                .isEqualTo("사본에서 수정");
        assertThat(requirementRepository.findByUuidAndWorkspace_Uuid("req_aaaaaaaa", original.getUuid()).orElseThrow().getName())
                .isEqualTo("로그인 v2");

        // 원본·사본을 모두 가진 사용자가 workspaceId 없이 부르면 고르지 않는다
        assertThatThrownBy(() -> designService.deleteApiSpec(null, "api_cccccccc", owner.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // 접근 못 하는 워크스페이스를 지정하면 403
        assertThatThrownBy(() -> designService.deleteApiSpec(copy.getUuid(), "api_cccccccc", invitee.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        // 워크스페이스를 지정한 삭제는 그 사본만 지운다
        designService.deleteApiSpec(copy.getUuid(), "api_cccccccc", owner.getId());
        em.flush();
        assertThat(apiSpecRepository.findAllByUuid("api_cccccccc"))
                .extracting(a -> a.getWorkspace().getUuid())
                .containsExactly(original.getUuid());

        // 이제 하나만 남았으니 옛 경로도 예전처럼 동작한다
        designService.updateApiSpec(null, "api_cccccccc", owner.getId(),
                new com.myide.backend.dto.design.DesignApiSpecRequest("POST", "/api/login", "d", "{}", "{}"));
    }

    @Test
    @DisplayName("복제본을 삭제해도 원본 DB 데이터는 그대로다")
    void deletingDuplicateKeepsOriginal() throws Exception {
        Workspace original = createOriginal(true);
        Map<String, Long> before = counts(original.getUuid());

        Workspace copy = duplicate(original, "지울 복제본");
        String copyId = copy.getUuid();

        workspaceService.deleteWorkspace(copyId, owner.getId());
        em.flush();
        em.clear();

        assertThat(workspaceRepository.findById(copyId)).isEmpty();
        assertThat(counts(copyId).values()).allMatch(v -> v == 0L);
        assertThat(counts(original.getUuid())).isEqualTo(before);
    }

    @Test
    @DisplayName("같은 이름의 폴더가 이미 있으면 409 이고 그 폴더를 건드리지 않는다")
    void duplicateNameConflict() throws Exception {
        Workspace original = createOriginal(false);
        Path existing = Path.of(original.getPath()).getParent().resolve("이미 있음");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("keep.txt"), "keep");

        assertThatThrownBy(() -> duplicate(original, "이미 있음"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(Files.readString(existing.resolve("keep.txt"))).isEqualTo("keep");
    }

    // ── 준비 ────────────────────────────────────────────────────────

    private Workspace createOriginal(boolean team) throws Exception {
        Path root = temp.resolve("원본");
        writeWorkspaceFolder(root);

        Workspace ws = team
                ? Workspace.createTeam(owner, "원본", "원본 설명", root.toString())
                : Workspace.createPersonal(owner, "원본", "원본 설명", root.toString());
        ws = workspaceRepository.save(ws);

        if (team) {
            memberRepository.save(WorkspaceMember.createOwner(ws, owner));
            memberRepository.save(WorkspaceMember.builder().workspace(ws).user(member)
                    .role(WorkspaceMember.WorkspaceRole.MEMBER).status(WorkspaceMember.JoinStatus.ACCEPTED).build());
            memberRepository.save(WorkspaceMember.builder().workspace(ws).user(invitee)
                    .role(WorkspaceMember.WorkspaceRole.MEMBER).status(WorkspaceMember.JoinStatus.PENDING).build());
        }

        projectRepository.save(Project.builder().name("app").description("앱").language(LanguageType.JAVA)
                .gitUrl("https://example.com/r.git").workspace(ws).build());

        Schedule todo = scheduleRepository.save(Schedule.builder().workspace(ws).createdBy(owner)
                .assignee(team ? member : owner).title("설계").description("ERD")
                .startDate(LocalDate.of(2026, 10, 1)).endDate(LocalDate.of(2026, 10, 3))
                .status(ScheduleStatus.TODO).category("Design").build());
        Schedule done = scheduleRepository.save(Schedule.builder().workspace(ws).createdBy(owner)
                .title("배포").startDate(LocalDate.of(2026, 10, 5)).endDate(LocalDate.of(2026, 10, 6))
                .status(ScheduleStatus.DONE).build());

        devlogRepository.save(Devlog.builder().workspace(ws).createdBy(owner).title("일반 일지").content("본문")
                .workedDate(LocalDate.of(2026, 10, 2)).tags(List.of("spring", "jpa")).build());
        devlogRepository.save(Devlog.builder().workspace(ws).schedule(done).createdBy(owner).title("연결 일지")
                .content("배포 완료").workedDate(LocalDate.of(2026, 10, 6)).tags(List.of("done")).build());

        notificationRepository.save(Notification.builder().receiver(owner).workspace(ws)
                .type(NotificationType.SCHEDULE).title("알림").message("과거 알림").build());

        treeRepository.save(VirtualFileTree.builder().workspaceId(ws.getUuid()).branchName("master")
                .viewName("언어별").criteria("언어별로").treeDataJson("[]").isActive(true).build());

        // 설계: 실제 서비스로 시드 → 체크포인트 → 수정 저장 → 체크포인트
        String projection = readProjection();
        snapshotService.seed(ws.getUuid(), owner.getId(),
                new DesignDocWriteRequest(2, "AAAAAAAAAAAAAAAAAAAA", codec.fromJson(projection)));
        snapshotService.createCheckpoint(ws.getUuid(), owner.getId(), "v1");
        snapshotService.save(ws.getUuid(), owner.getId(), new DesignDocWriteRequest(2, "AAAAAAAAAAAAAAAAAAAB",
                codec.fromJson(projection.replace("\"name\": \"로그인\",", "\"name\": \"로그인 v2\","))));
        snapshotService.createCheckpoint(ws.getUuid(), owner.getId(), "v2");

        em.flush();
        assertThat(todo.getId()).isNotNull();

        return ws;
    }

    /** 파일 몇 개와, 원본 절대경로가 박힌 worktree 연결(브랜치 dev) 한 쌍. */
    private void writeWorkspaceFolder(Path root) throws Exception {
        Path master = root.resolve("app/master");
        Path dev = root.resolve("app/dev");
        Path admin = master.resolve(".git/worktrees/dev");
        Files.createDirectories(admin);
        Files.createDirectories(dev);

        Files.writeString(master.resolve("Main.java"), "class Main {}\n");
        Files.writeString(dev.resolve("Main.java"), "class Main {}\n");
        Files.writeString(master.resolve(".git/HEAD"), "ref: refs/heads/master\n");
        Files.writeString(admin.resolve("gitdir"), dev.resolve(".git").toString().replace('\\', '/') + "\n");
        Files.writeString(dev.resolve(".git"), "gitdir: " + admin.toString().replace('\\', '/') + "\n");
    }

    private Workspace duplicate(Workspace original, String name) throws Exception {
        WorkspaceDuplicateRequest request = objectMapper.readValue(
                objectMapper.writeValueAsString(Map.of("name", name, "description", "복제 설명")),
                WorkspaceDuplicateRequest.class);

        Set<String> before = workspaceRepository.findAll().stream().map(Workspace::getUuid).collect(Collectors.toSet());

        workspaceService.duplicateWorkspace(original.getUuid(), owner.getId(), request);
        em.flush();
        em.clear();

        return workspaceRepository.findAll().stream()
                .filter(w -> !before.contains(w.getUuid()))
                .findFirst()
                .map(w -> workspaceRepository.findById(w.getUuid()).orElseThrow())
                .orElseThrow();
    }

    /** 워크스페이스에 딸린 행 수(원본 불변 확인용). */
    private Map<String, Long> counts(String workspaceId) {
        Map<String, Long> map = new java.util.TreeMap<>();
        map.put("members", memberRepository.findAll().stream().filter(m -> m.getWorkspace().getUuid().equals(workspaceId)).count());
        map.put("projects", (long) projectRepository.findByWorkspaceUuidOrderByUpdatedAtDesc(workspaceId).size());
        map.put("schedules", (long) scheduleRepository.findByWorkspace_UuidOrderByStartDateAscCreatedAtDesc(workspaceId).size());
        map.put("devlogs", (long) devlogRepository.findByWorkspace_UuidOrderByWorkedDateDescCreatedAtDesc(workspaceId).size());
        map.put("snapshot", snapshotRepository.findByWorkspace_Uuid(workspaceId).map(s -> 1L).orElse(0L));
        map.put("checkpoints", (long) checkpointRepository.findByWorkspace_UuidOrderByCreatedAtDesc(workspaceId).size());
        map.put("document", documentRepository.findByWorkspace_Uuid(workspaceId).map(s -> 1L).orElse(0L));
        map.put("requirements", (long) requirementRepository.findByWorkspace_UuidOrderByCreatedAtAsc(workspaceId).size());
        map.put("apis", (long) apiSpecRepository.findByWorkspace_UuidOrderByCreatedAtAsc(workspaceId).size());
        map.put("trees", (long) treeRepository.findByWorkspaceIdOrderByIdAsc(workspaceId).size());
        return map;
    }

    private long notificationsOf(Workspace ws) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getWorkspace() != null && n.getWorkspace().getUuid().equals(ws.getUuid()))
                .count();
    }

    private List<Schedule> schedules(Workspace ws) {
        return scheduleRepository.findByWorkspace_UuidOrderByStartDateAscCreatedAtDesc(ws.getUuid()).stream()
                .sorted(Comparator.comparing(Schedule::getStartDate))
                .toList();
    }

    private static Devlog byTitle(List<Devlog> devlogs, String title) {
        return devlogs.stream().filter(d -> d.getTitle().equals(title)).findFirst().orElseThrow();
    }

    private static User user(String name) {
        return User.builder().email(name + "@test.local").password("x").nickname(name).build();
    }

    private String readProjection() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("design/projection.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
