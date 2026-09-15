package com.myide.backend.dto;

import com.myide.backend.domain.report.ReportReason;
import com.myide.backend.domain.report.ReportStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class ReportDto {

    // ==========================================
    // 신고 요청
    // ==========================================

    @Getter
    @NoArgsConstructor
    public static class CreateRequest {

        private ReportReason reason;

        private String content;
    }


    // ==========================================
    // 신고 접수 결과
    // ==========================================

    @Getter
    @Builder
    public static class Response {

        private Long id;

        private Long postId;

        private Long reporterId;

        private ReportReason reason;

        private String content;

        private ReportStatus status;

        private LocalDateTime createdAt;
    }
}