package com.myide.backend.service;

import com.myide.backend.config.QuerydslConfig;
import com.myide.backend.domain.User;
import com.myide.backend.domain.devlog.DevlogScheduleStatusAfterWrite;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.schedule.ScheduleStatus;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import com.myide.backend.dto.devlog.DevlogCreateRequest;
import com.myide.backend.dto.devlog.DevlogResponse;
import com.myide.backend.dto.devlog.DevlogUpdateRequest;
import com.myide.backend.repository.DevlogRepository;
import com.myide.backend.repository.ScheduleRepository;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.workspace.WorkspaceMemberRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 개발일지 작성자 저장·반환과 작성자별 수정·삭제 권한.
 *
 * 실제 JPA 쿼리를 돌려야 지연 로딩·N+1 까지 보이므로 H2(MySQL 모드)로 띄운다.
 * 운영 DB 는 건드리지 않는다.
 */
@DataJpaTest
@Import({QuerydslConfig.class, DevlogService.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:devlog-author-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class DevlogServiceAuthorTest {

    @Autowired DevlogService devlogService;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository memberRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired DevlogRepository devlogRepository;
    @Autowired EntityManager em;

    private User owner;
    private User member;
    private User pending;
    private User outsider;
    private Workspace team;
    private Schedule schedule;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(user("owner-" + suffix));
        member = userRepository.save(user("member-" + suffix));
        pending = userRepository.save(user("pending-" + suffix));
        outsider = userRepository.save(user("outsider-" + suffix));

        team = workspaceRepository.save(Workspace.createTeam(owner, "팀", "설명", "C:/tmp/team-" + suffix));
        memberRepository.save(WorkspaceMember.createOwner(team, owner));
        memberRepository.save(WorkspaceMember.builder().workspace(team).user(member)
                .role(WorkspaceMember.WorkspaceRole.MEMBER).status(WorkspaceMember.JoinStatus.ACCEPTED).build());
        memberRepository.save(WorkspaceMember.builder().workspace(team).user(pending)
                .role(WorkspaceMember.WorkspaceRole.MEMBER).status(WorkspaceMember.JoinStatus.PENDING).build());

        // 담당자는 owner 인 일정
        schedule = scheduleRepository.save(Schedule.builder().workspace(team).createdBy(owner).assignee(owner)
                .title("예약 조회 API 구현").startDate(LocalDate.of(2026, 10, 1)).endDate(LocalDate.of(2026, 10, 3))
                .status(ScheduleStatus.TODO).build());
    }

    // ── 작성자 저장 · 반환 ───────────────────────────────────────────

    @Test
    @DisplayName("일반·일정 연결 일지 모두 요청한 사람이 작성자가 되고, 일정 담당자는 복사되지 않는다")
    void authorIsCurrentUser() {
        DevlogResponse general = devlogService.createDevlog(team.getUuid(), member.getId(), create(null));
        DevlogResponse linked = devlogService.createDevlog(team.getUuid(), member.getId(), create(schedule.getUuid()));

        assertThat(general.type()).isEqualTo("general");
        assertThat(general.authorId()).isEqualTo(member.getId());
        assertThat(general.authorName()).isEqualTo(member.getNickname());

        // 일정 담당자(owner) 와 작성자(member) 가 달라도 된다
        assertThat(linked.type()).isEqualTo("linked");
        assertThat(linked.authorId()).isEqualTo(member.getId());
        assertThat(linked.authorName()).isEqualTo(member.getNickname());
    }

    @Test
    @DisplayName("목록·상세·일정별 조회 모두 작성자 id·이름을 돌려주고, 팀원은 서로의 일지를 볼 수 있다")
    void readReturnsAuthor() {
        DevlogResponse byMember = devlogService.createDevlog(team.getUuid(), member.getId(), create(null));
        devlogService.createDevlog(team.getUuid(), owner.getId(), create(schedule.getUuid()));
        flushAndClear();

        List<DevlogResponse> seenByOwner = devlogService.getDevlogs(team.getUuid(), owner.getId(), null);
        assertThat(seenByOwner).extracting(DevlogResponse::authorId)
                .containsExactlyInAnyOrder(member.getId(), owner.getId());
        assertThat(seenByOwner).allMatch(d -> d.authorName() != null);

        DevlogResponse detail = devlogService.getDevlog(byMember.id(), owner.getId());
        assertThat(detail.authorId()).isEqualTo(member.getId());
        assertThat(detail.authorName()).isEqualTo(member.getNickname());

        assertThat(devlogService.getDevlogsBySchedule(schedule.getUuid(), member.getId()))
                .singleElement()
                .satisfies(d -> assertThat(d.authorId()).isEqualTo(owner.getId()));
    }

    @Test
    @DisplayName("작성자 이름은 조회 시점의 닉네임이다")
    void authorNameFollowsNickname() {
        DevlogResponse created = devlogService.createDevlog(team.getUuid(), member.getId(), create(null));

        userRepository.findById(member.getId()).orElseThrow().updateProfile("새닉네임", null);
        flushAndClear();

        assertThat(devlogService.getDevlog(created.id(), owner.getId()).authorName()).isEqualTo("새닉네임");
    }

    // ── 수정 · 삭제 권한 ─────────────────────────────────────────────

    @Test
    @DisplayName("작성자 본인은 수정·삭제할 수 있고, 수정해도 작성자는 그대로다")
    void authorCanEditAndDelete() {
        DevlogResponse created = devlogService.createDevlog(team.getUuid(), member.getId(), create(null));

        DevlogResponse updated = devlogService.updateDevlog(created.id(), member.getId(), update(schedule.getUuid()));
        flushAndClear();

        assertThat(updated.title()).isEqualTo("고친 제목");
        assertThat(updated.scheduleId()).isEqualTo(schedule.getUuid());
        assertThat(devlogService.getDevlog(created.id(), member.getId()).authorId()).isEqualTo(member.getId());

        devlogService.deleteDevlog(created.id(), member.getId());
        flushAndClear();

        assertThat(devlogRepository.findByUuid(created.id())).isEmpty();
    }

    @Test
    @DisplayName("다른 팀원의 일지는 방장이라도 수정·삭제할 수 없다(403), 원본은 그대로")
    void othersCannotEditOrDelete() {
        DevlogResponse created = devlogService.createDevlog(team.getUuid(), member.getId(), create(null));

        assertStatus(() -> devlogService.updateDevlog(created.id(), owner.getId(), update(null)), HttpStatus.FORBIDDEN);
        assertStatus(() -> devlogService.deleteDevlog(created.id(), owner.getId()), HttpStatus.FORBIDDEN);
        flushAndClear();

        DevlogResponse after = devlogService.getDevlog(created.id(), member.getId());
        assertThat(after.title()).isEqualTo("제목");
        assertThat(after.authorId()).isEqualTo(member.getId());
    }

    @Test
    @DisplayName("비로그인 401, 외부인·초대 대기자 403, 없는 일지 404")
    void accessErrors() {
        DevlogResponse created = devlogService.createDevlog(team.getUuid(), member.getId(), create(null));

        assertStatus(() -> devlogService.getDevlogs(team.getUuid(), null, null), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> devlogService.createDevlog(team.getUuid(), null, create(null)), HttpStatus.UNAUTHORIZED);

        for (User stranger : List.of(outsider, pending)) {
            assertStatus(() -> devlogService.getDevlogs(team.getUuid(), stranger.getId(), null), HttpStatus.FORBIDDEN);
            assertStatus(() -> devlogService.getDevlog(created.id(), stranger.getId()), HttpStatus.FORBIDDEN);
            assertStatus(() -> devlogService.createDevlog(team.getUuid(), stranger.getId(), create(null)), HttpStatus.FORBIDDEN);
            assertStatus(() -> devlogService.updateDevlog(created.id(), stranger.getId(), update(null)), HttpStatus.FORBIDDEN);
            assertStatus(() -> devlogService.deleteDevlog(created.id(), stranger.getId()), HttpStatus.FORBIDDEN);
            // 작성자 필터를 줘도 접근 검사는 그대로
            assertStatus(() -> devlogService.getDevlogs(team.getUuid(), stranger.getId(), String.valueOf(member.getId())),
                    HttpStatus.FORBIDDEN);
        }

        assertStatus(() -> devlogService.getDevlog("no-such-devlog", member.getId()), HttpStatus.NOT_FOUND);
        assertStatus(() -> devlogService.updateDevlog("no-such-devlog", member.getId(), update(null)), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("팀을 나간 작성자는 자기 일지라도 볼 수도 고칠 수도 없다")
    void formerMemberLosesAccess() {
        DevlogResponse created = devlogService.createDevlog(team.getUuid(), member.getId(), create(null));

        memberRepository.findByWorkspace_UuidAndUser_Id(team.getUuid(), member.getId())
                .ifPresent(memberRepository::delete);
        flushAndClear();

        assertStatus(() -> devlogService.updateDevlog(created.id(), member.getId(), update(null)), HttpStatus.FORBIDDEN);
        assertStatus(() -> devlogService.deleteDevlog(created.id(), member.getId()), HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("다른 워크스페이스의 일정에는 연결할 수 없다(400)")
    void cannotLinkForeignSchedule() {
        Workspace other = workspaceRepository.save(Workspace.createPersonal(owner, "개인", "", "C:/tmp/p-" + UUID.randomUUID()));
        Schedule foreign = scheduleRepository.save(Schedule.builder().workspace(other).createdBy(owner)
                .title("남의 일정").startDate(LocalDate.of(2026, 10, 1)).endDate(LocalDate.of(2026, 10, 2))
                .status(ScheduleStatus.TODO).build());

        assertStatus(() -> devlogService.createDevlog(team.getUuid(), member.getId(), create(foreign.getUuid())),
                HttpStatus.BAD_REQUEST);

        DevlogResponse created = devlogService.createDevlog(team.getUuid(), member.getId(), create(null));
        assertStatus(() -> devlogService.updateDevlog(created.id(), member.getId(), update(foreign.getUuid())),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("개인 프로젝트: 소유자만 보고 쓴다")
    void personalWorkspace() {
        Workspace personal = workspaceRepository.save(
                Workspace.createPersonal(member, "개인", "", "C:/tmp/p-" + UUID.randomUUID()));

        DevlogResponse mine = devlogService.createDevlog(personal.getUuid(), member.getId(), create(null));
        assertThat(mine.authorId()).isEqualTo(member.getId());

        // 같은 팀 동료라도 남의 개인 프로젝트는 볼 수 없다
        assertStatus(() -> devlogService.getDevlogs(personal.getUuid(), owner.getId(), null), HttpStatus.FORBIDDEN);
        assertStatus(() -> devlogService.getDevlog(mine.id(), owner.getId()), HttpStatus.FORBIDDEN);
    }

    // ── 작성자 필터 ──────────────────────────────────────────────────

    @Test
    @DisplayName("author 필터: 없음/all=전체, me=본인, 숫자=그 팀원, 그 밖의 값은 400")
    void authorFilter() {
        devlogService.createDevlog(team.getUuid(), member.getId(), create(null));
        devlogService.createDevlog(team.getUuid(), member.getId(), create(schedule.getUuid()));
        devlogService.createDevlog(team.getUuid(), owner.getId(), create(null));
        flushAndClear();

        assertThat(devlogService.getDevlogs(team.getUuid(), owner.getId(), null)).hasSize(3);
        assertThat(devlogService.getDevlogs(team.getUuid(), owner.getId(), "all")).hasSize(3);

        assertThat(devlogService.getDevlogs(team.getUuid(), owner.getId(), "me"))
                .extracting(DevlogResponse::authorId).containsExactly(owner.getId());
        assertThat(devlogService.getDevlogs(team.getUuid(), member.getId(), "me"))
                .extracting(DevlogResponse::authorId).containsOnly(member.getId()).hasSize(2);

        assertThat(devlogService.getDevlogs(team.getUuid(), owner.getId(), String.valueOf(member.getId())))
                .extracting(DevlogResponse::type).containsExactlyInAnyOrder("general", "linked");

        assertThat(devlogService.getDevlogs(team.getUuid(), owner.getId(), String.valueOf(outsider.getId()))).isEmpty();

        assertStatus(() -> devlogService.getDevlogs(team.getUuid(), owner.getId(), "someone"), HttpStatus.BAD_REQUEST);
    }

    // ── 기존 동작 · 쿼리 수 ─────────────────────────────────────────

    @Test
    @DisplayName("일지 작성 시 일정 진행 상태 변경은 그대로 동작한다")
    void scheduleStatusAfterWriteStillWorks() {
        DevlogCreateRequest request = new DevlogCreateRequest(schedule.getUuid(), "제목", "본문",
                LocalDate.of(2026, 10, 2), null, null, DevlogScheduleStatusAfterWrite.DONE);

        devlogService.createDevlog(team.getUuid(), member.getId(), request);
        flushAndClear();

        assertThat(scheduleRepository.findByUuid(schedule.getUuid()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.DONE);
    }

    @Test
    @DisplayName("목록 조회의 쿼리 수가 일지 수에 비례해 늘지 않는다(N+1 없음)")
    void listHasNoNPlusOne() {
        User[] authors = {owner, member};
        for (int i = 0; i < 10; i++) {
            devlogService.createDevlog(team.getUuid(), authors[i % 2].getId(), create(i % 2 == 0 ? null : schedule.getUuid()));
        }
        flushAndClear();

        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<DevlogResponse> list = devlogService.getDevlogs(team.getUuid(), owner.getId(), null);

        assertThat(list).hasSize(10).allMatch(d -> d.authorName() != null && !d.tags().isEmpty());
        // 워크스페이스 · 멤버 확인 · 일지(+일정·작성자) · 태그 묶음 — 일지 10건이어도 한 자릿수
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(5);
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private static DevlogCreateRequest create(String scheduleId) {
        return new DevlogCreateRequest(scheduleId, "제목", "본문", LocalDate.of(2026, 10, 2),
                null, null, DevlogScheduleStatusAfterWrite.NONE);
    }

    private static DevlogUpdateRequest update(String scheduleId) {
        return new DevlogUpdateRequest(scheduleId, "고친 제목", "고친 본문", LocalDate.of(2026, 10, 3), null, null);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status));
    }

    private static User user(String name) {
        return User.builder().email(name + "@test.local").password("x").nickname(name).build();
    }
}
