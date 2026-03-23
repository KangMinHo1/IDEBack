package com.myide.backend.controller;

import com.myide.backend.dto.schedule.ScheduleCreateRequest;
import com.myide.backend.dto.schedule.ScheduleResponse;
import com.myide.backend.dto.schedule.ScheduleSummaryResponse;
import com.myide.backend.dto.schedule.ScheduleTeamMemberResponse;
import com.myide.backend.dto.schedule.ScheduleUpdateRequest;
import com.myide.backend.dto.schedule.ScheduleWorkspaceOptionResponse;
import com.myide.backend.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /**
     * 일정 생성
     */
    @PostMapping
    public ScheduleResponse createSchedule(@RequestBody @Valid ScheduleCreateRequest request) {
        return scheduleService.createSchedule(request);
    }

    /**
     * 일정 상세
     */
    @GetMapping("/{scheduleId}")
    public ScheduleResponse getScheduleDetail(@PathVariable Long scheduleId) {
        return scheduleService.getScheduleDetail(scheduleId);
    }

    /**
     * 일정 수정
     */
    @PutMapping("/{scheduleId}")
    public ScheduleResponse updateSchedule(
            @PathVariable Long scheduleId,
            @RequestBody @Valid ScheduleUpdateRequest request
    ) {
        return scheduleService.updateSchedule(scheduleId, request);
    }

    /**
     * 일정 삭제
     */
    @DeleteMapping("/{scheduleId}")
    public void deleteSchedule(@PathVariable Long scheduleId) {
        scheduleService.deleteSchedule(scheduleId);
    }

    /**
     * 특정 날짜 일정 조회
     * view = personal | team | all
     */
    @GetMapping
    public List<ScheduleResponse> getSchedulesByDate(
            @RequestParam String view,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false, defaultValue = "all") String category
    ) {
        return scheduleService.getSchedulesByDate(view, date, workspaceId, category);
    }

    /**
     * 월간 캘린더 일정 조회
     */
    @GetMapping("/calendar")
    public List<ScheduleResponse> getSchedulesForMonth(
            @RequestParam String view,
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) String workspaceId
    ) {
        return scheduleService.getSchedulesForMonth(view, year, month, workspaceId);
    }

    /**
     * 이번 주 일정
     */
    @GetMapping("/weekly")
    public List<ScheduleResponse> getSchedulesForWeek(
            @RequestParam String view,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String workspaceId
    ) {
        return scheduleService.getSchedulesForWeek(view, date, workspaceId);
    }

    /**
     * 이번달 전체 / 오늘 / 이번주 카운트
     */
    @GetMapping("/summary")
    public ScheduleSummaryResponse getSummary(
            @RequestParam String view,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String workspaceId
    ) {
        return scheduleService.getSummary(view, date, workspaceId);
    }

    /**
     * 개인 워크스페이스 드롭다운
     */
    @GetMapping("/personal/workspaces")
    public List<ScheduleWorkspaceOptionResponse> getMyPersonalWorkspaces() {
        return scheduleService.getMyPersonalWorkspaces();
    }

    /**
     * 팀 워크스페이스 드롭다운
     */
    @GetMapping("/team/workspaces")
    public List<ScheduleWorkspaceOptionResponse> getMyTeamWorkspaces() {
        return scheduleService.getMyTeamWorkspaces();
    }

    /**
     * 팀 멤버 목록
     */
    @GetMapping("/team/workspaces/{workspaceId}/members")
    public List<ScheduleTeamMemberResponse> getWorkspaceMembers(@PathVariable String workspaceId) {
        return scheduleService.getWorkspaceMembers(workspaceId);
    }
}