package com.myide.backend.domain;

import com.myide.backend.domain.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "schedules")
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * PERSONAL / TEAM
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleType type;

    /**
     * 일정 생성자
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User creator;

    /**
     * 일정이 속한 워크스페이스
     * - PERSONAL 일정이면 PERSONAL 워크스페이스
     * - TEAM 일정이면 TEAM 워크스페이스
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 100)
    private String title;

    /**
     * 기간 일정 대응
     */
    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /**
     * Work / Meeting / Study / Etc
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ScheduleCategory category;

    @Column(length = 100)
    private String location;

    /**
     * 기획 / 설계 / 구현 / 마무리
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ScheduleStage stage;

    /**
     * 프론트엔드 / 백엔드 / 디자인 / 풀스택
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ScheduleRole role;

    /**
     * 해야 함 / 진행중 / 완료
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ScheduleStatus status;

    /**
     * 팀 일정에서만 사용
     * 예: "효주, 민수"
     */
    @Column(length = 255)
    private String participants;

    @Column(columnDefinition = "TEXT")
    private String description;

    public enum ScheduleType {
        PERSONAL, TEAM
    }

    public enum ScheduleCategory {
        WORK, MEETING, STUDY, ETC
    }

    public enum ScheduleStage {
        PLANNING, DESIGN, IMPLEMENTATION, WRAPUP
    }

    public enum ScheduleRole {
        FRONTEND, BACKEND, DESIGN, FULLSTACK
    }

    public enum ScheduleStatus {
        TODO, IN_PROGRESS, DONE
    }

    public static Schedule createPersonal(
            User creator,
            Workspace workspace,
            String title,
            LocalDate startDate,
            LocalDate endDate,
            ScheduleCategory category,
            String location,
            ScheduleStage stage,
            ScheduleRole role,
            ScheduleStatus status,
            String description
    ) {
        return Schedule.builder()
                .type(ScheduleType.PERSONAL)
                .creator(creator)
                .workspace(workspace)
                .title(title)
                .startDate(startDate)
                .endDate(endDate)
                .category(category)
                .location(location)
                .stage(stage)
                .role(role)
                .status(status)
                .description(description)
                .build();
    }

    public static Schedule createTeam(
            User creator,
            Workspace workspace,
            String title,
            LocalDate startDate,
            LocalDate endDate,
            ScheduleCategory category,
            String location,
            ScheduleStage stage,
            ScheduleRole role,
            ScheduleStatus status,
            String participants,
            String description
    ) {
        return Schedule.builder()
                .type(ScheduleType.TEAM)
                .creator(creator)
                .workspace(workspace)
                .title(title)
                .startDate(startDate)
                .endDate(endDate)
                .category(category)
                .location(location)
                .stage(stage)
                .role(role)
                .status(status)
                .participants(participants)
                .description(description)
                .build();
    }

    public void update(
            String title,
            LocalDate startDate,
            LocalDate endDate,
            ScheduleCategory category,
            String location,
            ScheduleStage stage,
            ScheduleRole role,
            ScheduleStatus status,
            String participants,
            String description
    ) {
        this.title = title;
        this.startDate = startDate;
        this.endDate = endDate;
        this.category = category;
        this.location = location;
        this.stage = stage;
        this.role = role;
        this.status = status;
        this.participants = participants;
        this.description = description;
    }
}