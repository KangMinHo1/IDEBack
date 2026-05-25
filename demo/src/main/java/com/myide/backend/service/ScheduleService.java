package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.schedule.ScheduleCreateRequest;
import com.myide.backend.dto.schedule.SchedulePeriodUpdateRequest;
import com.myide.backend.dto.schedule.ScheduleResponse;
import com.myide.backend.dto.schedule.ScheduleStatusUpdateRequest;
import com.myide.backend.dto.schedule.ScheduleUpdateRequest;
import com.myide.backend.repository.DevlogRepository;
import com.myide.backend.repository.ScheduleRepository;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final DevlogRepository devlogRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;

    public List<ScheduleResponse> getSchedules(String workspaceId, Long userId) {
        validateUserId(userId);
        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);

        return scheduleRepository
                .findByWorkspace_UuidOrderByStartDateAscCreatedAtDesc(workspace.getUuid())
                .stream()
                .map(schedule ->
                        ScheduleResponse.from(
                                schedule,
                                devlogRepository.existsBySchedule_Uuid(schedule.getUuid())
                        )
                )
                .toList();
    }

    public List<ScheduleResponse> getSchedulesInRange(
            String workspaceId,
            Long userId,
            LocalDate rangeStart,
            LocalDate rangeEnd
    ) {
        validateUserId(userId);
        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);

        return scheduleRepository
                .findByWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscCreatedAtDesc(
                        workspace.getUuid(),
                        rangeEnd,
                        rangeStart
                )
                .stream()
                .map(schedule ->
                        ScheduleResponse.from(
                                schedule,
                                devlogRepository.existsBySchedule_Uuid(schedule.getUuid())
                        )
                )
                .toList();
    }

    @Transactional
    public ScheduleResponse createSchedule(
            String workspaceId,
            Long userId,
            ScheduleCreateRequest request
    ) {
        validateUserId(userId);
        validateTitle(request.title());
        validatePeriod(request.startDate(), request.endDate());

        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);
        User user = getUser(userId);

        Schedule schedule = Schedule.builder()
                .workspace(workspace)
                .createdBy(user)
                .title(request.title().trim())
                .description(normalizeDescription(request.description()))
                .startDate(request.startDate())
                .endDate(request.endDate())
                .status(request.status())
                .build();

        Schedule saved = scheduleRepository.save(schedule);

        return ScheduleResponse.from(saved, false);
    }

    @Transactional
    public ScheduleResponse updateSchedule(
            String scheduleId,
            Long userId,
            ScheduleUpdateRequest request
    ) {
        validateUserId(userId);
        validateTitle(request.title());
        validatePeriod(request.startDate(), request.endDate());

        if (request.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상태는 필수입니다.");
        }

        Schedule schedule = getAccessibleSchedule(scheduleId, userId);
        schedule.updateContent(
                request.title().trim(),
                normalizeDescription(request.description()),
                request.startDate(),
                request.endDate(),
                request.status(),
                schedule.getCategory()
        );

        return ScheduleResponse.from(
                schedule,
                devlogRepository.existsBySchedule_Uuid(schedule.getUuid())
        );
    }

    @Transactional
    public ScheduleResponse updateStatus(
            String scheduleId,
            Long userId,
            ScheduleStatusUpdateRequest request
    ) {
        validateUserId(userId);

        Schedule schedule = getAccessibleSchedule(scheduleId, userId);
        schedule.updateStatus(request.status());

        return ScheduleResponse.from(
                schedule,
                devlogRepository.existsBySchedule_Uuid(schedule.getUuid())
        );
    }

    @Transactional
    public ScheduleResponse updatePeriod(
            String scheduleId,
            Long userId,
            SchedulePeriodUpdateRequest request
    ) {
        validateUserId(userId);
        validatePeriod(request.startDate(), request.endDate());

        Schedule schedule = getAccessibleSchedule(scheduleId, userId);
        schedule.updatePeriod(request.startDate(), request.endDate());

        return ScheduleResponse.from(
                schedule,
                devlogRepository.existsBySchedule_Uuid(schedule.getUuid())
        );
    }

    @Transactional
    public void deleteSchedule(String scheduleId, Long userId) {
        validateUserId(userId);

        Schedule schedule = getAccessibleSchedule(scheduleId, userId);
        scheduleRepository.delete(schedule);
    }

    private Schedule getAccessibleSchedule(String scheduleId, Long userId) {
        Schedule schedule = scheduleRepository.findByUuid(scheduleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."));

        getAccessibleWorkspace(schedule.getWorkspace().getUuid(), userId);

        return schedule;
    }

    private Workspace getAccessibleWorkspace(String workspaceId, Long userId) {
        return workspaceRepository.findMyAllWorkspaces(userId)
                .stream()
                .filter(workspace -> workspace.getUuid().equals(workspaceId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 접근 권한이 없습니다."));
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

    private void validateTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "일정 제목은 필수입니다.");
        }
    }

    private void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시작일과 종료일은 필수입니다.");
        }

        if (endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "종료일은 시작일보다 빠를 수 없습니다.");
        }
    }

    private String normalizeDescription(String description) {
        if (description == null || description.trim().isEmpty()) {
            return "등록된 상세 내용이 없습니다.";
        }

        return description.trim();
    }
}
