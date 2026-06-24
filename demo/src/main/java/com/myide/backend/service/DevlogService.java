package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.devlog.Devlog;
import com.myide.backend.domain.devlog.DevlogScheduleStatusAfterWrite;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.schedule.ScheduleStatus;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.devlog.DevlogCreateRequest;
import com.myide.backend.dto.devlog.DevlogResponse;
import com.myide.backend.dto.devlog.DevlogUpdateRequest;
import com.myide.backend.repository.DevlogRepository;
import com.myide.backend.repository.ScheduleRepository;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.myide.backend.domain.workspace.WorkspaceMember;
import com.myide.backend.repository.workspace.WorkspaceMemberRepository;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DevlogService {

    private final DevlogRepository devlogRepository;
    private final ScheduleRepository scheduleRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;

    private final WorkspaceMemberRepository workspaceMemberRepository;

    public List<DevlogResponse> getDevlogs(String workspaceId, Long userId) {
        validateUserId(userId);
        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);

        return devlogRepository
                .findByWorkspace_UuidOrderByWorkedDateDescCreatedAtDesc(workspace.getUuid())
                .stream()
                .map(DevlogResponse::from)
                .toList();
    }

    public DevlogResponse getDevlog(String devlogId, Long userId) {
        validateUserId(userId);

        Devlog devlog = getAccessibleDevlog(devlogId, userId);

        return DevlogResponse.from(devlog);
    }

    public List<DevlogResponse> getDevlogsBySchedule(String scheduleId, Long userId) {
        validateUserId(userId);

        Schedule schedule = getAccessibleSchedule(scheduleId, userId);

        return devlogRepository
                .findBySchedule_UuidOrderByWorkedDateDescCreatedAtDesc(schedule.getUuid())
                .stream()
                .map(DevlogResponse::from)
                .toList();
    }

    @Transactional
    public DevlogResponse createDevlog(
            String workspaceId,
            Long userId,
            DevlogCreateRequest request
    ) {
        validateUserId(userId);

        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);
        User user = getUser(userId);

        Schedule schedule = resolveSchedule(workspace.getUuid(), request.scheduleId());

        Devlog devlog = Devlog.builder()
                .workspace(workspace)
                .schedule(schedule)
                .createdBy(user)
                .title(request.title())
                .content(request.content())
                .workedDate(request.workedDate())
                .category(request.category())
                .tags(normalizeTags(request.tags(), schedule))
                .build();

        applyScheduleStatusAfterWrite(schedule, request.scheduleStatusAfterWrite());

        Devlog saved = devlogRepository.save(devlog);

        return DevlogResponse.from(saved);
    }

    @Transactional
    public DevlogResponse updateDevlog(
            String devlogId,
            Long userId,
            DevlogUpdateRequest request
    ) {
        validateUserId(userId);

        Devlog devlog = getAccessibleDevlog(devlogId, userId);
        Workspace workspace = devlog.getWorkspace();

        Schedule schedule = resolveSchedule(workspace.getUuid(), request.scheduleId());

        devlog.update(
                schedule,
                request.title(),
                request.content(),
                request.workedDate(),
                request.category(),
                normalizeTags(request.tags(), schedule)
        );

        return DevlogResponse.from(devlog);
    }

    @Transactional
    public void deleteDevlog(String devlogId, Long userId) {
        validateUserId(userId);

        Devlog devlog = getAccessibleDevlog(devlogId, userId);
        devlogRepository.delete(devlog);
    }

    private Schedule resolveSchedule(String workspaceId, String scheduleId) {
        if (scheduleId == null || scheduleId.isBlank()) {
            return null;
        }

        return scheduleRepository.findByUuidAndWorkspace_Uuid(scheduleId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "연결할 일정을 찾을 수 없습니다."));
    }

    private void applyScheduleStatusAfterWrite(
            Schedule schedule,
            DevlogScheduleStatusAfterWrite statusAfterWrite
    ) {
        if (schedule == null) {
            return;
        }

        DevlogScheduleStatusAfterWrite next =
                statusAfterWrite == null ? DevlogScheduleStatusAfterWrite.NONE : statusAfterWrite;

        if (next == DevlogScheduleStatusAfterWrite.PROGRESS) {
            schedule.updateStatus(ScheduleStatus.PROGRESS);
        }

        if (next == DevlogScheduleStatusAfterWrite.DONE) {
            schedule.updateStatus(ScheduleStatus.DONE);
        }
    }

    private List<String> normalizeTags(List<String> tags, Schedule schedule) {
        if (tags != null && !tags.isEmpty()) {
            return tags.stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        }

        List<String> defaultTags = new ArrayList<>();

        if (schedule == null) {
            defaultTags.add("General");
            defaultTags.add("Memo");
        } else {
            defaultTags.add("Schedule");
            defaultTags.add(schedule.getStatus().getValue());
        }

        return defaultTags;
    }

    private Devlog getAccessibleDevlog(String devlogId, Long userId) {
        Devlog devlog = devlogRepository.findByUuid(devlogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "개발일지를 찾을 수 없습니다."));

        getAccessibleWorkspace(devlog.getWorkspace().getUuid(), userId);

        return devlog;
    }

    private Schedule getAccessibleSchedule(String scheduleId, Long userId) {
        Schedule schedule = scheduleRepository.findByUuid(scheduleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."));

        getAccessibleWorkspace(schedule.getWorkspace().getUuid(), userId);

        return schedule;
    }

    private Workspace getAccessibleWorkspace(String workspaceId, Long userId) {
        validateUserId(userId);

        Workspace workspace = workspaceRepository.findByUuid(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "워크스페이스를 찾을 수 없습니다."
                ));

        boolean isOwner =
                workspace.getOwner() != null &&
                        workspace.getOwner().getId().equals(userId);

        boolean isAcceptedMember =
                workspaceMemberRepository.existsByWorkspace_UuidAndUser_IdAndStatus(
                        workspaceId,
                        userId,
                        WorkspaceMember.JoinStatus.ACCEPTED
                );

        if (isOwner || isAcceptedMember) {
            return workspace;
        }

        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "워크스페이스 접근 권한이 없습니다."
        );
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
    }
}