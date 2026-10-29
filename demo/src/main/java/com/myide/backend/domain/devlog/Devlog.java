package com.myide.backend.domain.devlog;

import com.myide.backend.domain.User;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

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

    // 실제로 일지를 쓴 사람. 일정 담당자(Schedule.assignee)와는 다른 개념이다.
    // 수정해도 바뀌지 않는다(updatable = false 는 DDL 을 바꾸지 않는다).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private LocalDate workedDate;

    @Column(nullable = false, length = 50)
    private String category;

    // 목록 응답이 일지마다 태그를 읽으므로, 한 건씩 따로 가져오지 않게 묶어서 읽는다.
    @ElementCollection
    @BatchSize(size = 100)
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

    /**
     * 프로젝트 복제용 사본.
     *
     * 일정과 연결된 개발일지는 원본 일정이 아니라 복제된 일정을 가리켜야 한다.
     * 그래서 연결할 일정을 밖에서 받는다(일반 개발일지면 null).
     * 작성 시각도 원본 그대로 옮긴다 — 목록이 작업일·작성시각 순이라
     * 시각을 새로 찍으면 같은 날 쓴 일지의 순서가 뒤바뀐다.
     */
    public Devlog duplicateTo(Workspace targetWorkspace, Schedule targetSchedule) {
        Devlog copy = new Devlog();

        copy.uuid = UUID.randomUUID().toString();
        copy.workspace = targetWorkspace;
        copy.schedule = targetSchedule;
        copy.createdBy = this.createdBy;
        copy.title = this.title;
        copy.content = this.content;
        copy.workedDate = this.workedDate;
        copy.category = this.category;
        copy.tags = new ArrayList<>(this.tags);
        copy.createdAt = this.createdAt;
        copy.updatedAt = this.updatedAt;

        return copy;
    }

    /**
     * 작성자 본인인지.
     *
     * 작성자를 알 수 없는 일지는 누구의 것도 아니다(false) — 작성자가 비어 있다는
     * 이유로 수정·삭제 검사가 풀리면 안 된다.
     * 지연 로딩 프록시라도 getId() 는 추가 조회 없이 외래키 값만 읽는다.
     */
    public boolean isWrittenBy(Long userId) {
        return userId != null
                && createdBy != null
                && userId.equals(createdBy.getId());
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

        // 복제본(duplicateTo)은 원본 시각을 들고 오므로 비어 있을 때만 채운다.
        if (this.createdAt == null) {
            this.createdAt = now;
        }

        if (this.updatedAt == null) {
            this.updatedAt = now;
        }

        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}