package com.myide.backend.domain.report;

import com.myide.backend.domain.BaseTimeEntity;
import com.myide.backend.domain.post.Post;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "post_reports",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_post_report_post_reporter",
                        columnNames = {"post_id", "reporter_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_post_report_post",
                        columnList = "post_id"
                ),
                @Index(
                        name = "idx_post_report_reporter",
                        columnList = "reporter_id"
                ),
                @Index(
                        name = "idx_post_report_status",
                        columnList = "status"
                )
        }
)
public class Report extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 신고된 게시글
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "post_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_post_report_post")
    )
    private Post post;

    // 신고한 사용자 ID
    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    // 신고 사유
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportReason reason;

    // 상세 신고 내용
    @Column(length = 500)
    private String content;

    // 신고 처리 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportStatus status;

    // 관리자 처리 시각
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Builder
    public Report(
            Post post,
            Long reporterId,
            ReportReason reason,
            String content
    ) {
        this.post = post;
        this.reporterId = reporterId;
        this.reason = reason;
        this.content = content;
        this.status = ReportStatus.PENDING;
    }

    // 나중에 관리자 기능에서 사용 가능
    public void changeStatus(ReportStatus status) {
        this.status = status;

        if (status == ReportStatus.RESOLVED ||
                status == ReportStatus.REJECTED) {

            this.processedAt = LocalDateTime.now();

        } else {
            this.processedAt = null;
        }
    }
}