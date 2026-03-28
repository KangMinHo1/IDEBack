package com.myide.backend.service;

import com.myide.backend.domain.Schedule;
import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import com.myide.backend.domain.workspace.WorkspaceType;
import com.myide.backend.dto.schedule.ScheduleCreateRequest;
import com.myide.backend.dto.schedule.ScheduleProgressResponse;
import com.myide.backend.dto.schedule.ScheduleResponse;
import com.myide.backend.dto.schedule.ScheduleSummaryResponse;
import com.myide.backend.dto.schedule.ScheduleTeamMemberResponse;
import com.myide.backend.dto.schedule.ScheduleUpdateRequest;
import com.myide.backend.dto.schedule.ScheduleWorkspaceOptionResponse;
import com.myide.backend.exception.ApiException;
import com.myide.backend.repository.ScheduleRepository;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.workspace.WorkspaceMemberRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public ScheduleResponse createSchedule(ScheduleCreateRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        User currentUser = getUserOrThrow(currentUserId);

        validateDateRange(request.getStartDate(), request.getEndDate());

        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "워크스페이스를 찾을 수 없습니다."));

        validateWorkspaceMatchesScheduleType(workspace, request.getType());

        Schedule schedule;

        if (request.getType() == Schedule.ScheduleType.PERSONAL) {
            validatePersonalWorkspaceAccessible(workspace, currentUserId);

            schedule = Schedule.createPersonal(
                    currentUser,
                    workspace,
                    request.getTitle(),
                    request.getStartDate(),
                    request.getEndDate(),
                    request.getCategory(),
                    request.getLocation(),
                    request.getStage(),
                    request.getRole(),
                    request.getStatus(),
                    request.getDescription()
            );
        } else {
            validateTeamAccessible(workspace, currentUserId);

            schedule = Schedule.createTeam(
                    currentUser,
                    workspace,
                    request.getTitle(),
                    request.getStartDate(),
                    request.getEndDate(),
                    request.getCategory(),
                    request.getLocation(),
                    request.getStage(),
                    request.getRole(),
                    request.getStatus(),
                    request.getParticipants(),
                    request.getDescription()
            );
        }

        return ScheduleResponse.from(scheduleRepository.save(schedule));
    }

    public ScheduleResponse getScheduleDetail(Long scheduleId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."));

        validateScheduleAccessible(schedule, currentUserId);

        return ScheduleResponse.from(schedule);
    }

    public ScheduleResponse updateSchedule(Long scheduleId, ScheduleUpdateRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        validateDateRange(request.getStartDate(), request.getEndDate());

        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."));

        validateScheduleEditable(schedule, currentUserId);

        schedule.update(
                request.getTitle(),
                request.getStartDate(),
                request.getEndDate(),
                request.getCategory(),
                request.getLocation(),
                request.getStage(),
                request.getRole(),
                request.getStatus(),
                schedule.getType() == Schedule.ScheduleType.TEAM ? request.getParticipants() : null,
                request.getDescription()
        );

        return ScheduleResponse.from(schedule);
    }

    public void deleteSchedule(Long scheduleId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."));

        validateScheduleEditable(schedule, currentUserId);

        scheduleRepository.delete(schedule);
    }

    /**
     * 진행률 조회
     * 현재 구조에서는 Schedule이 project와 연결되어 있지 않으므로 workspace 기준으로 계산
     */
    public ScheduleProgressResponse getProgress(String view, String workspaceId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        if (!"personal".equalsIgnoreCase(view) && !"team".equalsIgnoreCase(view)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "진행률 조회는 personal 또는 team view만 지원합니다.");
        }

        Workspace workspace = getWorkspaceOrThrow(workspaceId);

        Schedule.ScheduleType targetType;
        if ("personal".equalsIgnoreCase(view)) {
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.PERSONAL);
            validatePersonalWorkspaceAccessible(workspace, currentUserId);
            targetType = Schedule.ScheduleType.PERSONAL;
        } else {
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.TEAM);
            validateTeamAccessible(workspace, currentUserId);
            targetType = Schedule.ScheduleType.TEAM;
        }

        long totalCount = scheduleRepository.countByTypeAndWorkspace_Uuid(
                targetType,
                workspaceId
        );

        long doneCount = scheduleRepository.countByTypeAndWorkspace_UuidAndStatus(
                targetType,
                workspaceId,
                Schedule.ScheduleStatus.DONE
        );

        int progress = calculateProgress(totalCount, doneCount);

        return ScheduleProgressResponse.builder()
                .workspaceId(workspace.getUuid())
                .workspaceName(workspace.getName())
                .type(targetType.name())
                .totalCount(totalCount)
                .doneCount(doneCount)
                .progress(progress)
                .build();
    }

    private int calculateProgress(long totalCount, long doneCount) {
        if (totalCount <= 0) {
            return 0;
        }
        return (int) Math.round((doneCount * 100.0) / totalCount);
    }

    /**
     * 특정 날짜 일정 조회
     * view = personal | team | all
     *
     * - personal: PERSONAL workspaceId 필수
     * - team: TEAM workspaceId 필수
     * - all: 접근 가능한 모든 PERSONAL/TEAM 워크스페이스 일정 조회
     */
    public List<ScheduleResponse> getSchedulesByDate(
            String view,
            LocalDate date,
            String workspaceId,
            String category
    ) {
        Long currentUserId = currentUserService.getCurrentUserId();
        List<Schedule> result = new ArrayList<>();

        Schedule.ScheduleCategory categoryEnum = parseCategory(category);

        if ("personal".equalsIgnoreCase(view)) {
            Workspace workspace = getWorkspaceOrThrow(workspaceId);
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.PERSONAL);
            validatePersonalWorkspaceAccessible(workspace, currentUserId);

            if (categoryEnum == null) {
                result.addAll(scheduleRepository
                        .findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
                                Schedule.ScheduleType.PERSONAL,
                                workspaceId,
                                date,
                                date
                        ));
            } else {
                result.addAll(scheduleRepository
                        .findByTypeAndWorkspace_UuidAndCategoryAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
                                Schedule.ScheduleType.PERSONAL,
                                workspaceId,
                                categoryEnum,
                                date,
                                date
                        ));
            }
        } else if ("team".equalsIgnoreCase(view)) {
            Workspace workspace = getWorkspaceOrThrow(workspaceId);
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.TEAM);
            validateTeamAccessible(workspace, currentUserId);

            if (categoryEnum == null) {
                result.addAll(scheduleRepository
                        .findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
                                Schedule.ScheduleType.TEAM,
                                workspaceId,
                                date,
                                date
                        ));
            } else {
                result.addAll(scheduleRepository
                        .findByTypeAndWorkspace_UuidAndCategoryAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
                                Schedule.ScheduleType.TEAM,
                                workspaceId,
                                categoryEnum,
                                date,
                                date
                        ));
            }
        } else if ("all".equalsIgnoreCase(view)) {
            List<Workspace> myWorkspaces = workspaceRepository.findMyAllWorkspaces(currentUserId);

            for (Workspace workspace : myWorkspaces) {
                Schedule.ScheduleType targetType =
                        workspace.getType() == WorkspaceType.PERSONAL
                                ? Schedule.ScheduleType.PERSONAL
                                : Schedule.ScheduleType.TEAM;

                if (categoryEnum == null) {
                    result.addAll(scheduleRepository
                            .findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
                                    targetType,
                                    workspace.getUuid(),
                                    date,
                                    date
                            ));
                } else {
                    result.addAll(scheduleRepository
                            .findByTypeAndWorkspace_UuidAndCategoryAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
                                    targetType,
                                    workspace.getUuid(),
                                    categoryEnum,
                                    date,
                                    date
                            ));
                }
            }
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "view는 personal, team, all 중 하나여야 합니다.");
        }

        result.sort(Comparator.comparing(Schedule::getStartDate).thenComparing(Schedule::getId).reversed());

        return result.stream()
                .map(ScheduleResponse::from)
                .toList();
    }

    /**
     * 월간 캘린더 조회
     */
    public List<ScheduleResponse> getSchedulesForMonth(
            String view,
            int year,
            int month,
            String workspaceId
    ) {
        Long currentUserId = currentUserService.getCurrentUserId();

        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());

        List<Schedule> result = new ArrayList<>();

        if ("personal".equalsIgnoreCase(view)) {
            Workspace workspace = getWorkspaceOrThrow(workspaceId);
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.PERSONAL);
            validatePersonalWorkspaceAccessible(workspace, currentUserId);

            result.addAll(scheduleRepository
                    .findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            Schedule.ScheduleType.PERSONAL,
                            workspaceId,
                            monthEnd,
                            monthStart
                    ));
        } else if ("team".equalsIgnoreCase(view)) {
            Workspace workspace = getWorkspaceOrThrow(workspaceId);
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.TEAM);
            validateTeamAccessible(workspace, currentUserId);

            result.addAll(scheduleRepository
                    .findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            Schedule.ScheduleType.TEAM,
                            workspaceId,
                            monthEnd,
                            monthStart
                    ));
        } else if ("all".equalsIgnoreCase(view)) {
            List<Workspace> myWorkspaces = workspaceRepository.findMyAllWorkspaces(currentUserId);

            for (Workspace workspace : myWorkspaces) {
                Schedule.ScheduleType targetType =
                        workspace.getType() == WorkspaceType.PERSONAL
                                ? Schedule.ScheduleType.PERSONAL
                                : Schedule.ScheduleType.TEAM;

                result.addAll(scheduleRepository
                        .findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                                targetType,
                                workspace.getUuid(),
                                monthEnd,
                                monthStart
                        ));
            }
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "view는 personal, team, all 중 하나여야 합니다.");
        }

        return result.stream()
                .map(ScheduleResponse::from)
                .toList();
    }

    /**
     * 이번 주 일정
     */
    public List<ScheduleResponse> getSchedulesForWeek(
            String view,
            LocalDate baseDate,
            String workspaceId
    ) {
        Long currentUserId = currentUserService.getCurrentUserId();

        LocalDate weekStart = baseDate.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = baseDate.with(DayOfWeek.SUNDAY);

        List<Schedule> result = new ArrayList<>();

        if ("personal".equalsIgnoreCase(view)) {
            Workspace workspace = getWorkspaceOrThrow(workspaceId);
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.PERSONAL);
            validatePersonalWorkspaceAccessible(workspace, currentUserId);

            result.addAll(scheduleRepository
                    .findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
                            Schedule.ScheduleType.PERSONAL,
                            workspaceId,
                            weekEnd,
                            weekStart
                    ));
        } else if ("team".equalsIgnoreCase(view)) {
            Workspace workspace = getWorkspaceOrThrow(workspaceId);
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.TEAM);
            validateTeamAccessible(workspace, currentUserId);

            result.addAll(scheduleRepository
                    .findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
                            Schedule.ScheduleType.TEAM,
                            workspaceId,
                            weekEnd,
                            weekStart
                    ));
        } else if ("all".equalsIgnoreCase(view)) {
            List<Workspace> myWorkspaces = workspaceRepository.findMyAllWorkspaces(currentUserId);

            for (Workspace workspace : myWorkspaces) {
                Schedule.ScheduleType targetType =
                        workspace.getType() == WorkspaceType.PERSONAL
                                ? Schedule.ScheduleType.PERSONAL
                                : Schedule.ScheduleType.TEAM;

                result.addAll(scheduleRepository
                        .findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
                                targetType,
                                workspace.getUuid(),
                                weekEnd,
                                weekStart
                        ));
            }
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "view는 personal, team, all 중 하나여야 합니다.");
        }

        result.sort(Comparator.comparing(Schedule::getStartDate).thenComparing(Schedule::getId).reversed());

        return result.stream()
                .map(ScheduleResponse::from)
                .toList();
    }

    /**
     * 이번 달 전체 / 오늘 / 이번 주 통계
     */
    public ScheduleSummaryResponse getSummary(
            String view,
            LocalDate baseDate,
            String workspaceId
    ) {
        Long currentUserId = currentUserService.getCurrentUserId();

        LocalDate monthStart = baseDate.withDayOfMonth(1);
        LocalDate monthEnd = baseDate.withDayOfMonth(baseDate.lengthOfMonth());
        LocalDate today = baseDate;
        LocalDate weekStart = baseDate.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = baseDate.with(DayOfWeek.SUNDAY);

        long monthCount = 0;
        long todayCount = 0;
        long weekCount = 0;

        if ("personal".equalsIgnoreCase(view)) {
            Workspace workspace = getWorkspaceOrThrow(workspaceId);
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.PERSONAL);
            validatePersonalWorkspaceAccessible(workspace, currentUserId);

            monthCount = scheduleRepository.countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    Schedule.ScheduleType.PERSONAL, workspaceId, monthEnd, monthStart
            );
            todayCount = scheduleRepository.countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    Schedule.ScheduleType.PERSONAL, workspaceId, today, today
            );
            weekCount = scheduleRepository.countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    Schedule.ScheduleType.PERSONAL, workspaceId, weekEnd, weekStart
            );
        } else if ("team".equalsIgnoreCase(view)) {
            Workspace workspace = getWorkspaceOrThrow(workspaceId);
            validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.TEAM);
            validateTeamAccessible(workspace, currentUserId);

            monthCount = scheduleRepository.countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    Schedule.ScheduleType.TEAM, workspaceId, monthEnd, monthStart
            );
            todayCount = scheduleRepository.countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    Schedule.ScheduleType.TEAM, workspaceId, today, today
            );
            weekCount = scheduleRepository.countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    Schedule.ScheduleType.TEAM, workspaceId, weekEnd, weekStart
            );
        } else if ("all".equalsIgnoreCase(view)) {
            List<Workspace> myWorkspaces = workspaceRepository.findMyAllWorkspaces(currentUserId);

            for (Workspace workspace : myWorkspaces) {
                Schedule.ScheduleType targetType =
                        workspace.getType() == WorkspaceType.PERSONAL
                                ? Schedule.ScheduleType.PERSONAL
                                : Schedule.ScheduleType.TEAM;

                monthCount += scheduleRepository.countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        targetType, workspace.getUuid(), monthEnd, monthStart
                );
                todayCount += scheduleRepository.countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        targetType, workspace.getUuid(), today, today
                );
                weekCount += scheduleRepository.countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        targetType, workspace.getUuid(), weekEnd, weekStart
                );
            }
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "view는 personal, team, all 중 하나여야 합니다.");
        }

        return ScheduleSummaryResponse.builder()
                .monthCount(monthCount)
                .todayCount(todayCount)
                .weekCount(weekCount)
                .build();
    }

    public List<ScheduleWorkspaceOptionResponse> getMyPersonalWorkspaces() {
        Long currentUserId = currentUserService.getCurrentUserId();

        return workspaceRepository.findMyAllWorkspaces(currentUserId).stream()
                .filter(workspace -> workspace.getType() == WorkspaceType.PERSONAL)
                .map(workspace -> ScheduleWorkspaceOptionResponse.builder()
                        .workspaceId(workspace.getUuid())
                        .workspaceName(workspace.getName())
                        .build())
                .toList();
    }

    public List<ScheduleWorkspaceOptionResponse> getMyTeamWorkspaces() {
        Long currentUserId = currentUserService.getCurrentUserId();

        return workspaceRepository.findMyAllWorkspaces(currentUserId).stream()
                .filter(workspace -> workspace.getType() == WorkspaceType.TEAM)
                .map(workspace -> ScheduleWorkspaceOptionResponse.builder()
                        .workspaceId(workspace.getUuid())
                        .workspaceName(workspace.getName())
                        .build())
                .toList();
    }

    public List<ScheduleTeamMemberResponse> getWorkspaceMembers(String workspaceId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "워크스페이스를 찾을 수 없습니다."));

        validateWorkspaceMatchesScheduleType(workspace, Schedule.ScheduleType.TEAM);
        validateTeamAccessible(workspace, currentUserId);

        return workspaceMemberRepository.findByWorkspace_UuidAndStatus(
                        workspaceId,
                        WorkspaceMember.JoinStatus.ACCEPTED
                ).stream()
                .map(member -> ScheduleTeamMemberResponse.builder()
                        .userId(member.getUser().getId())
                        .name(member.getUser().getNickname())
                        .build())
                .toList();
    }

    private User getUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    private Workspace getWorkspaceOrThrow(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "workspaceId가 필요합니다.");
        }

        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "워크스페이스를 찾을 수 없습니다."));
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "종료일은 시작일보다 빠를 수 없습니다.");
        }
    }

    private void validateWorkspaceMatchesScheduleType(Workspace workspace, Schedule.ScheduleType scheduleType) {
        if (scheduleType == Schedule.ScheduleType.PERSONAL
                && workspace.getType() != WorkspaceType.PERSONAL) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "개인 일정은 PERSONAL 워크스페이스에만 생성/조회할 수 있습니다.");
        }

        if (scheduleType == Schedule.ScheduleType.TEAM
                && workspace.getType() != WorkspaceType.TEAM) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "팀 일정은 TEAM 워크스페이스에만 생성/조회할 수 있습니다.");
        }
    }

    private void validatePersonalWorkspaceAccessible(Workspace workspace, Long currentUserId) {
        if (workspace.getType() != WorkspaceType.PERSONAL) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "개인 일정은 PERSONAL 워크스페이스에서만 사용할 수 있습니다.");
        }

        boolean isOwner = workspace.getOwner() != null
                && workspace.getOwner().getId().equals(currentUserId);

        if (!isOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, "해당 개인 워크스페이스에 접근할 권한이 없습니다.");
        }
    }

    private void validateTeamAccessible(Workspace workspace, Long currentUserId) {
        boolean isOwner = workspace.getOwner() != null
                && workspace.getOwner().getId().equals(currentUserId);

        boolean isAcceptedMember = workspaceMemberRepository
                .findByWorkspace_UuidAndUser_Id(workspace.getUuid(), currentUserId)
                .filter(member -> member.getStatus() == WorkspaceMember.JoinStatus.ACCEPTED)
                .isPresent();

        if (!isOwner && !isAcceptedMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "해당 팀 워크스페이스에 접근할 권한이 없습니다.");
        }
    }

    private void validateScheduleAccessible(Schedule schedule, Long currentUserId) {
        if (schedule.getType() == Schedule.ScheduleType.PERSONAL) {
            validateWorkspaceMatchesScheduleType(schedule.getWorkspace(), Schedule.ScheduleType.PERSONAL);
            validatePersonalWorkspaceAccessible(schedule.getWorkspace(), currentUserId);

            if (!schedule.getCreator().getId().equals(currentUserId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "해당 개인 일정에 접근할 수 없습니다.");
            }
            return;
        }

        validateWorkspaceMatchesScheduleType(schedule.getWorkspace(), Schedule.ScheduleType.TEAM);
        validateTeamAccessible(schedule.getWorkspace(), currentUserId);
    }

    private void validateScheduleEditable(Schedule schedule, Long currentUserId) {
        if (schedule.getType() == Schedule.ScheduleType.PERSONAL) {
            validateWorkspaceMatchesScheduleType(schedule.getWorkspace(), Schedule.ScheduleType.PERSONAL);
            validatePersonalWorkspaceAccessible(schedule.getWorkspace(), currentUserId);

            if (!schedule.getCreator().getId().equals(currentUserId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "해당 개인 일정을 수정/삭제할 수 없습니다.");
            }
            return;
        }

        validateWorkspaceMatchesScheduleType(schedule.getWorkspace(), Schedule.ScheduleType.TEAM);
        validateTeamAccessible(schedule.getWorkspace(), currentUserId);
    }

    private Schedule.ScheduleCategory parseCategory(String category) {
        if (category == null || category.isBlank() || "all".equalsIgnoreCase(category)) {
            return null;
        }

        try {
            return Schedule.ScheduleCategory.valueOf(category.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "유효하지 않은 카테고리입니다.");
        }
    }
}