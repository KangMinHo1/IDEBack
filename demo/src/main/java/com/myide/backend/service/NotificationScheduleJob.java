package com.myide.backend.service;

import com.myide.backend.domain.notification.NotificationType;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class NotificationScheduleJob {

    private final ScheduleRepository scheduleRepository;
    private final NotificationService notificationService;

    // 매일 오전 9시 오늘 일정 알림 생성
    @Scheduled(cron = "0 0 9 * * *")
    public void notifyTodaySchedules() {
        LocalDate today = LocalDate.now();

        List<Schedule> todaySchedules =
                scheduleRepository.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(today, today);

        for (Schedule schedule : todaySchedules) {
            String workspaceId = schedule.getWorkspace().getUuid();

            notificationService.notifyWorkspaceMembersExcept(
                    workspaceId,
                    null,
                    NotificationType.SCHEDULE,
                    "오늘 일정 알림",
                    "오늘 진행 예정인 일정이 있습니다: " + schedule.getTitle(),
                    "/schedules?view=" + schedule.getWorkspace().getType().name().toLowerCase()
                            + "&workspaceId=" + workspaceId
            );
        }
    }
}