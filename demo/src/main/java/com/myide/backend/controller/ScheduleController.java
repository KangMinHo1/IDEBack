package com.myide.backend.controller;

import com.myide.backend.dto.schedule.ScheduleCreateRequest;
import com.myide.backend.dto.schedule.ScheduleProgressResponse;
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

    @PostMapping
    public ScheduleResponse createSchedule(@RequestBody @Valid ScheduleCreateRequest request) {
        return scheduleService.createSchedule(request);
    }

    @GetMapping("/calendar")
    public List<ScheduleResponse> getSchedulesForMonth(
            @RequestParam String view,
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) String workspaceId
    ) {
        return scheduleService.getSchedulesForMonth(view, year, month, workspaceId);
    }

    @GetMapping("/weekly")
    public List<ScheduleResponse> getSchedulesForWeek(
            @RequestParam String view,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String workspaceId
    ) {
        return scheduleService.getSchedulesForWeek(view, date, workspaceId);
    }

    @GetMapping("/latest")
    public List<ScheduleResponse> getLatestSchedules(
            @RequestParam String view,
            @RequestParam String workspaceId,
            @RequestParam(defaultValue = "3") int size
    ) {
        return scheduleService.getLatestSchedules(view, workspaceId, size);
    }

    @GetMapping("/summary")
    public ScheduleSummaryResponse getSummary(
            @RequestParam String view,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String workspaceId
    ) {
        return scheduleService.getSummary(view, date, workspaceId);
    }

    @GetMapping("/progress")
    public ScheduleProgressResponse getProgress(
            @RequestParam String view,
            @RequestParam String workspaceId
    ) {
        return scheduleService.getProgress(view, workspaceId);
    }

    @GetMapping("/personal/workspaces")
    public List<ScheduleWorkspaceOptionResponse> getMyPersonalWorkspaces() {
        return scheduleService.getMyPersonalWorkspaces();
    }

    @GetMapping("/team/workspaces")
    public List<ScheduleWorkspaceOptionResponse> getMyTeamWorkspaces() {
        return scheduleService.getMyTeamWorkspaces();
    }

    @GetMapping("/team/workspaces/{workspaceId}/members")
    public List<ScheduleTeamMemberResponse> getWorkspaceMembers(@PathVariable String workspaceId) {
        return scheduleService.getWorkspaceMembers(workspaceId);
    }

    @GetMapping
    public List<ScheduleResponse> getSchedulesByDate(
            @RequestParam String view,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false, defaultValue = "all") String category
    ) {
        return scheduleService.getSchedulesByDate(view, date, workspaceId, category);
    }

    @GetMapping("/{scheduleId:\\d+}")
    public ScheduleResponse getScheduleDetail(@PathVariable Long scheduleId) {
        return scheduleService.getScheduleDetail(scheduleId);
    }

    @PutMapping("/{scheduleId:\\d+}")
    public ScheduleResponse updateSchedule(
            @PathVariable Long scheduleId,
            @RequestBody @Valid ScheduleUpdateRequest request
    ) {
        return scheduleService.updateSchedule(scheduleId, request);
    }

    @DeleteMapping("/{scheduleId:\\d+}")
    public void deleteSchedule(@PathVariable Long scheduleId) {
        scheduleService.deleteSchedule(scheduleId);
    }
}