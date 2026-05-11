package com.myide.backend.dto.devlog;

import com.myide.backend.domain.devlog.DevlogScheduleStatusAfterWrite;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record DevlogCreateRequest(
        // null 또는 빈 문자열이면 일반 개발일지
        String scheduleId,

        @NotBlank(message = "개발일지 제목은 필수입니다.")
        String title,

        @NotBlank(message = "개발일지 내용은 필수입니다.")
        String content,

        @NotNull(message = "작업한 날짜는 필수입니다.")
        LocalDate workedDate,

        String category,

        List<String> tags,

        DevlogScheduleStatusAfterWrite scheduleStatusAfterWrite
){}