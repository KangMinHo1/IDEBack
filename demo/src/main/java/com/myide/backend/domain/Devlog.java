package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Devlog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;


    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 300)
    private String summary;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(length = 500)
    private String tags;




    /**
     * 작성 날짜(캘린더 표시용)
     */
    @Column(nullable = false)
    @Builder.Default
    private LocalDate date = LocalDate.now();

    /**
     * 새로 추가되는 필드들
     */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String stage = "implementation"; // planning / implementation / wrapup

    @Column(columnDefinition = "TEXT")
    private String goal;

    @Column(columnDefinition = "TEXT")
    private String design;


    @Column(columnDefinition = "TEXT")
    private String issue;

    @Column(columnDefinition = "TEXT")
    private String solution;

    @Column(columnDefinition = "TEXT")
    private String nextPlan;

    @Column(length = 255)
    private String commitHash;

    @Builder.Default
    private Integer progress = 0;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (date == null) date = LocalDate.now();
        if (stage == null || stage.isBlank()) stage = "implementation";
        if (progress == null) progress = 0;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}