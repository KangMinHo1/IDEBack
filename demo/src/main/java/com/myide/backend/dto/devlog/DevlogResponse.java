package com.myide.backend.dto.devlog;

import com.myide.backend.domain.User;
import com.myide.backend.domain.devlog.Devlog;
import com.myide.backend.domain.schedule.Schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DevlogResponse(
        String id,
        String workspaceId,
        String projectName,
        String title,
        String content,
        LocalDate workedDate,
        String type,
        String scheduleId,
        String scheduleTitle,
        String scheduleStatus,
        String category,
        List<String> tags,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,

        // 작성자(Devlog.createdBy). 일정 담당자가 아니다.
        // 이름은 저장해 두지 않고 조회 시점의 닉네임을 내보낸다(닉네임을 바꿔도 따라간다).
        Long authorId,
        String authorName
) {
    public static DevlogResponse from(Devlog devlog) {
        Schedule schedule = devlog.getSchedule();
        User author = devlog.getCreatedBy();

        return new DevlogResponse(
                devlog.getUuid(),
                devlog.getWorkspace().getUuid(),
                devlog.getWorkspace().getName(),
                devlog.getTitle(),
                devlog.getContent(),
                devlog.getWorkedDate(),
                schedule == null ? "general" : "linked",
                schedule == null ? null : schedule.getUuid(),
                schedule == null ? null : schedule.getTitle(),
                schedule == null ? null : schedule.getStatus().getValue(),
                devlog.getCategory(),
                devlog.getTags(),
                devlog.getCreatedAt(),
                devlog.getUpdatedAt(),
                author == null ? null : author.getId(),
                author == null ? null : author.getNickname()
        );
    }
}
