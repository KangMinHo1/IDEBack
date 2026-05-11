package com.myide.backend.domain.devlog;

import com.myide.backend.domain.User;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "devlogs")
public class Devlog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 프론트와 API에서 사용할 공개 식별자
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_uuid", nullable = false)
    private Workspace workspace;

    // 일반 개발일지일 경우 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private LocalDate workedDate;

    @Column(nullable = false, length = 50)
    private String category;

    @ElementCollection
    @CollectionTable(
            name = "devlog_tags",
            joinColumns = @JoinColumn(name = "devlog_id")
    )
    @Column(name = "tag", length = 50)
    private List<String> tags = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Devlog(
            Workspace workspace,
            Schedule schedule,
            User createdBy,
            String title,
            String content,
            LocalDate workedDate,
            String category,
            List<String> tags
    ) {
        this.uuid = UUID.randomUUID().toString();
        this.workspace = workspace;
        this.schedule = schedule;
        this.createdBy = createdBy;
        this.title = title;
        this.content = content;
        this.workedDate = workedDate;
        this.category = category == null || category.isBlank() ? "General" : category;
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public void update(
            Schedule schedule,
            String title,
            String content,
            LocalDate workedDate,
            String category,
            List<String> tags
    ) {
        this.schedule = schedule;
        this.title = title;
        this.content = content;
        this.workedDate = workedDate;
        this.category = category == null || category.isBlank() ? "General" : category;
        this.tags.clear();

        if (tags != null) {
            this.tags.addAll(tags);
        }
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;

        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}