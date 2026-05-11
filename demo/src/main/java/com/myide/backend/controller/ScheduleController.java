package com.myide.backend.controller;

import com.myide.backend.dto.schedule.ScheduleCreateRequest;
import com.myide.backend.dto.schedule.SchedulePeriodUpdateRequest;
import com.myide.backend.dto.schedule.ScheduleResponse;
import com.myide.backend.dto.schedule.ScheduleStatusUpdateRequest;
import com.myide.backend.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping("/workspaces/{workspaceId}/schedules")
    public List<ScheduleResponse> getSchedules(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate
    ) {
        if (startDate != null && endDate != null) {
            return scheduleService.getSchedulesInRange(workspaceId, userId, startDate, endDate);
        }

        return scheduleService.getSchedules(workspaceId, userId);
    }

    @PostMapping("/workspaces/{workspaceId}/schedules")
    public ScheduleResponse createSchedule(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ScheduleCreateRequest request
    ) {
        return scheduleService.createSchedule(workspaceId, userId, request);
    }

    @PatchMapping("/schedules/{scheduleId}/status")
    public ScheduleResponse updateStatus(
            @PathVariable String scheduleId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ScheduleStatusUpdateRequest request
    ) {
        return scheduleService.updateStatus(scheduleId, userId, request);
    }

    @PatchMapping("/schedules/{scheduleId}/period")
    public ScheduleResponse updatePeriod(
            @PathVariable String scheduleId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody SchedulePeriodUpdateRequest request
    ) {
        return scheduleService.updatePeriod(scheduleId, userId, request);
    }

    @DeleteMapping("/schedules/{scheduleId}")
    public void deleteSchedule(
            @PathVariable String scheduleId,
            @AuthenticationPrincipal Long userId
    ) {
        scheduleService.deleteSchedule(scheduleId, userId);
    }
}