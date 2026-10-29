package com.myide.backend.domain.report;

import com.myide.backend.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * comment_reports 테이블 정의용 엔티티.
 * 읽기/쓰기는 CommunityInteractionService 가 JdbcTemplate 으로 하고, 이 클래스는 ddl-auto 로 테이블을 만들기 위해 둡니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "comment_reports",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_comment_report_comment_reporter", columnNames = {"comment_id", "reporter_id"})
        },
        indexes = {
                @Index(name = "idx_comment_report_reporter", columnList = "reporter_id"),
                @Index(name = "idx_comment_report_status", columnList = "status")
        }
)
public class CommentReport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportReason reason;

    @Column(length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportStatus status;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
