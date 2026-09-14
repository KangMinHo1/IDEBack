package com.myide.backend.domain.schedule;

import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "schedules")
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_uuid", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_user_id")
    private User assignee;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /*
     * 일정이 실제 DONE 상태가 된 시각.
     *
     * updatedAt과 분리해야 이후 일정 내용을 수정해도
     * "일정 완료" 활동 시간이 바뀌지 않는다.
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Builder
    private Schedule(
            Workspace workspace,
            User createdBy,
            User assignee,
            String title,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            ScheduleStatus status,
            String category
    ) {
        this.uuid = UUID.randomUUID().toString();
        this.workspace = workspace;
        this.createdBy = createdBy;

        this.assignee =
                assignee != null
                        ? assignee
                        : createdBy;

        this.title = title;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;

        this.status =
                status == null
                        ? ScheduleStatus.TODO
                        : status;

        this.category =
                category == null || category.isBlank()
                        ? "General"
                        : category;

        if (this.status == ScheduleStatus.DONE) {
            this.completedAt = LocalDateTime.now();
        }
    }

    public void updateStatus(ScheduleStatus status) {
        applyStatus(status);
    }

    public void updatePeriod(
            LocalDate startDate,
            LocalDate endDate
    ) {
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public void updateAssignee(User assignee) {
        this.assignee = assignee;
    }

    public void updateContent(
            String title,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            ScheduleStatus status,
            String category,
            User assignee
    ) {
        this.title = title;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;

        applyStatus(status);

        this.category =
                category == null || category.isBlank()
                        ? "General"
                        : category;

        if (assignee != null) {
            this.assignee = assignee;
        }
    }

    /*
     * DONE으로 처음 변경된 순간만 completedAt 기록.
     *
     * DONE -> DONE
     *   completedAt 유지
     *
     * TODO/IN_PROGRESS -> DONE
     *   현재 시간 저장
     *
     * DONE -> TODO/IN_PROGRESS
     *   완료 취소이므로 completedAt 제거
     */
    private void applyStatus(ScheduleStatus nextStatus) {
        if (nextStatus == null) {
            return;
        }

        ScheduleStatus previousStatus =
                this.status;

        if (
                nextStatus == ScheduleStatus.DONE
                        && previousStatus != ScheduleStatus.DONE
        ) {
            this.completedAt =
                    LocalDateTime.now();
        }

        if (nextStatus != ScheduleStatus.DONE) {
            this.completedAt = null;
        }

        this.status = nextStatus;
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now =
                LocalDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;

        if (this.uuid == null) {
            this.uuid =
                    UUID.randomUUID().toString();
        }

        if (this.assignee == null) {
            this.assignee =
                    this.createdBy;
        }

        /*
         * 혹시 생성 단계에서 DONE 상태인데
         * completedAt이 비어 있다면 보정.
         */
        if (
                this.status == ScheduleStatus.DONE
                        && this.completedAt == null
        ) {
            this.completedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt =
                LocalDateTime.now();
    }
}