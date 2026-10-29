package com.myide.backend.service.workspace;

import com.myide.backend.domain.User;
import com.myide.backend.domain.design.DesignApiSpec;
import com.myide.backend.domain.design.DesignDocCheckpoint;
import com.myide.backend.domain.design.DesignRequirement;
import com.myide.backend.domain.devlog.Devlog;
import com.myide.backend.domain.rearrange.VirtualFileTree;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import com.myide.backend.domain.workspace.WorkspaceType;
import com.myide.backend.repository.DevlogRepository;
import com.myide.backend.repository.ScheduleRepository;
import com.myide.backend.repository.design.DesignApiSpecRepository;
import com.myide.backend.repository.design.DesignDocCheckpointRepository;
import com.myide.backend.repository.design.DesignDocSnapshotRepository;
import com.myide.backend.repository.design.DesignDocumentRepository;
import com.myide.backend.repository.design.DesignRequirementRepository;
import com.myide.backend.repository.rearrange.VirtualFileTreeRepository;
import com.myide.backend.repository.workspace.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 프로젝트(워크스페이스) 복제 때 DB 쪽 작업 데이터를 새 워크스페이스로 옮긴다.
 *
 * 폴더 복사와 Workspace·Project 행 생성은 WorkspaceService.duplicateWorkspace 가 하고,
 * 그 트랜잭션 안에서 이 클래스를 부른다. 어느 단계에서 예외가 나도 같은 트랜잭션이
 * 통째로 롤백되도록 MANDATORY 로 묶었다(혼자 커밋되는 일이 없게).
 *
 * 원칙
 *  - 원본 행은 읽기만 한다. 사본은 전부 새 엔티티(new)로 만들어 id 를 새로 받는다.
 *  - 행끼리의 연결(개발일지 → 일정)은 원본 id → 사본 매핑으로 다시 잇는다.
 *  - 사용자(User) 자체는 복제하지 않는다. 작성자·담당자는 같은 회원을 가리킨다.
 *
 * 옮기지 않는 것(일부러)
 *  - 알림, 채팅 기록, 음성 채널의 접속 상태: 과거 기록이거나 휘발성 상태다.
 *  - CodeMapCache·CodeSummary: 파일에서 다시 만들 수 있는 캐시다.
 *  - 안전 실행·샌드박스 폴더: 실행할 때마다 다시 만드는 임시 사본이다.
 *  - 설계 AI 초안 작업: 메모리에만 있는 개인 작업 상태다.
 *  - API 테스터(api_saved_test / api_test_history): 워크스페이스 열이 없는 전역 테이블이라
 *    프로젝트에 딸린 데이터가 아니다. 복제하면 오히려 같은 목록에 두 벌이 생긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class WorkspaceDataCloner {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ScheduleRepository scheduleRepository;
    private final DevlogRepository devlogRepository;
    private final DesignDocSnapshotRepository designDocSnapshotRepository;
    private final DesignDocCheckpointRepository designDocCheckpointRepository;
    private final DesignDocumentRepository designDocumentRepository;
    private final DesignRequirementRepository designRequirementRepository;
    private final DesignApiSpecRepository designApiSpecRepository;
    private final VirtualFileTreeRepository virtualFileTreeRepository;

    /** 무엇을 몇 건 옮겼는지. 로그와 테스트에서 쓴다. */
    public record CloneResult(
            int members,
            int schedules,
            int devlogs,
            int linkedDevlogs,
            boolean designSnapshot,
            int designCheckpoints,
            boolean designDocument,
            int designRequirements,
            int designApiSpecs,
            int virtualFileTrees
    ) {}

    public CloneResult cloneAll(Workspace source, Workspace target) {
        int members = cloneMembers(source, target);

        Map<Long, Schedule> scheduleMap = cloneSchedules(source, target);
        int[] devlogCounts = cloneDevlogs(source, target, scheduleMap);

        DesignCounts design = cloneDesign(source, target);

        int trees = cloneVirtualFileTrees(source, target);

        CloneResult result = new CloneResult(
                members,
                scheduleMap.size(),
                devlogCounts[0],
                devlogCounts[1],
                design.snapshot,
                design.checkpoints,
                design.document,
                design.requirements,
                design.apiSpecs,
                trees
        );

        log.info("📄 [프로젝트 복제] {} → {} : {}", source.getUuid(), target.getUuid(), result);

        return result;
    }

    // ── 팀원 ────────────────────────────────────────────────────────

    /**
     * TEAM 이면 OWNER 행을 새로 만들고, 수락(ACCEPTED)한 팀원을 같은 권한으로 옮긴다.
     * 초대 대기(PENDING)는 옮기지 않는다 — 사본에 대한 초대에 동의한 적이 없다.
     *
     * OWNER 는 원본 멤버 행이 아니라 워크스페이스 소유자로 만든다. 예전 데이터에는
     * OWNER 멤버 행이 없는 팀도 있어서, 행을 따라가면 사본에 방장이 빠질 수 있다.
     */
    int cloneMembers(Workspace source, Workspace target) {
        User owner = target.getOwner();
        Set<Long> added = new HashSet<>();
        int count = 0;

        if (target.getType() == WorkspaceType.TEAM) {
            workspaceMemberRepository.save(WorkspaceMember.createOwner(target, owner));
            added.add(owner.getId());
            count++;
        }

        List<WorkspaceMember> accepted = workspaceMemberRepository
                .findByWorkspace_UuidAndStatus(source.getUuid(), WorkspaceMember.JoinStatus.ACCEPTED);

        for (WorkspaceMember member : accepted) {
            Long memberUserId = member.getUser().getId();

            if (Objects.equals(memberUserId, owner.getId()) || !added.add(memberUserId)) {
                continue;
            }

            workspaceMemberRepository.save(WorkspaceMember.builder()
                    .workspace(target)
                    .user(member.getUser())
                    .role(member.getRole())
                    .status(WorkspaceMember.JoinStatus.ACCEPTED)
                    .build());
            count++;
        }

        return count;
    }

    // ── 일정 · 개발일지 ─────────────────────────────────────────────

    /** @return 원본 일정 id → 사본 일정. 개발일지의 일정 연결을 다시 잇는 데 쓴다. */
    Map<Long, Schedule> cloneSchedules(Workspace source, Workspace target) {
        Map<Long, Schedule> map = new HashMap<>();

        for (Schedule schedule : scheduleRepository
                .findByWorkspace_UuidOrderByStartDateAscCreatedAtDesc(source.getUuid())) {
            map.put(schedule.getId(), scheduleRepository.save(schedule.duplicateTo(target)));
        }

        return map;
    }

    /** @return [전체 건수, 일정과 연결된 건수] */
    int[] cloneDevlogs(Workspace source, Workspace target, Map<Long, Schedule> scheduleMap) {
        int total = 0;
        int linked = 0;

        for (Devlog devlog : devlogRepository
                .findByWorkspace_UuidOrderByWorkedDateDescCreatedAtDesc(source.getUuid())) {
            Schedule targetSchedule = null;

            if (devlog.getSchedule() != null) {
                targetSchedule = scheduleMap.get(devlog.getSchedule().getId());

                // 다른 워크스페이스의 일정에 걸려 있던 예전 데이터라면 매핑이 없다.
                // 원본 일정을 그대로 가리키면 사본이 원본에 묶이므로 일반 일지로 옮긴다.
                if (targetSchedule == null) {
                    log.warn("⚠️ [프로젝트 복제] 개발일지 {} 의 일정 {} 이 원본 워크스페이스에 없어 연결 없이 복제합니다.",
                            devlog.getUuid(), devlog.getSchedule().getId());
                } else {
                    linked++;
                }
            }

            devlogRepository.save(devlog.duplicateTo(target, targetSchedule));
            total++;
        }

        return new int[]{total, linked};
    }

    // ── 설계 ────────────────────────────────────────────────────────

    private record DesignCounts(boolean snapshot, int checkpoints, boolean document,
                                int requirements, int apiSpecs) {}

    /**
     * 설계 저장본(Yjs + 평문 사본), 체크포인트, 그리고 역투영 행(옛 형식 문서·요구사항·API)을
     * 옮긴다. 역투영 행까지 옮기는 이유: 자료실·마이페이지는 옛 엔드포인트만 읽어서,
     * 사본을 누군가 열어 자동 저장하기 전까지 그 화면들이 비어 보이기 때문이다.
     *
     * 요구사항·API 행의 uuid 는 Yjs 항목 id 와 같아야 하므로 원본 값을 그대로 쓴다.
     * 전역 UNIQUE 인덱스가 아직 DB 에 남아 있으면(마이그레이션 전) 여기서 충돌하므로,
     * 즉시 flush 해 원인을 알 수 있는 메시지로 바꿔 던진다(트랜잭션은 롤백된다).
     */
    DesignCounts cloneDesign(Workspace source, Workspace target) {
        boolean snapshot = designDocSnapshotRepository.findByWorkspace_Uuid(source.getUuid())
                .map(row -> designDocSnapshotRepository.save(row.duplicateTo(target)))
                .isPresent();

        List<DesignDocCheckpoint> checkpoints =
                designDocCheckpointRepository.findByWorkspace_UuidOrderByCreatedAtDesc(source.getUuid());
        checkpoints.forEach(row -> designDocCheckpointRepository.save(row.duplicateTo(target)));

        boolean document = designDocumentRepository.findByWorkspace_Uuid(source.getUuid())
                .map(row -> designDocumentRepository.save(row.duplicateTo(target)))
                .isPresent();

        List<DesignRequirement> requirements =
                designRequirementRepository.findByWorkspace_UuidOrderByCreatedAtAsc(source.getUuid());
        List<DesignApiSpec> apiSpecs =
                designApiSpecRepository.findByWorkspace_UuidOrderByCreatedAtAsc(source.getUuid());

        try {
            requirements.forEach(row -> designRequirementRepository.save(row.duplicateTo(target)));
            apiSpecs.forEach(row -> designApiSpecRepository.save(row.duplicateTo(target)));

            designRequirementRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "설계 데이터 복제 중 ID 충돌이 발생했습니다. design_requirements / design_api_specs 의 "
                            + "uuid 전역 UNIQUE 인덱스를 제거하는 마이그레이션"
                            + "(docs/migrations/2026-10-27-design-uuid-scope.sql)이 필요합니다.",
                    e);
        }

        return new DesignCounts(snapshot, checkpoints.size(), document, requirements.size(), apiSpecs.size());
    }

    // ── 가상 파일 트리 ───────────────────────────────────────────────

    /**
     * AI 로 만들고 손으로 다듬은 "보기용" 트리. 파일 경로가 브랜치 폴더 기준 상대
     * 경로(realPath)라 폴더를 통째로 복사한 사본에서도 그대로 맞는다. 캐시처럼
     * 다시 만들 수 있는 것이 아니고(AI 호출 + 사용자 편집) 활성 상태도 화면 설정이라
     * 그대로 옮긴다.
     */
    int cloneVirtualFileTrees(Workspace source, Workspace target) {
        List<VirtualFileTree> trees = virtualFileTreeRepository.findByWorkspaceIdOrderByIdAsc(source.getUuid());

        for (VirtualFileTree tree : trees) {
            virtualFileTreeRepository.save(VirtualFileTree.builder()
                    .workspaceId(target.getUuid())
                    .branchName(tree.getBranchName())
                    .viewName(tree.getViewName())
                    .criteria(tree.getCriteria())
                    .treeDataJson(tree.getTreeDataJson())
                    .isActive(tree.isActive())
                    .createdAt(tree.getCreatedAt())
                    .build());
        }

        return trees.size();
    }
}
