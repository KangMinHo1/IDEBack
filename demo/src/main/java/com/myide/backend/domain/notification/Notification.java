package com.myide.backend.domain.notification;

import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notification_receiver_created", columnList = "receiver_id, created_at DESC"),
        @Index(name = "idx_notification_receiver_read", columnList = "receiver_id, is_read"),
        @Index(name = "idx_notification_type_created", columnList = "type, created_at DESC")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 알림을 받는 사용자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    // 관련 워크스페이스. 게시판처럼 전역 알림이면 null 가능.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id")
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String message;

    // 클릭했을 때 이동할 프론트 경로
    @Column(length = 500)
    private String targetUrl;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime readAt;

    @Builder
    private Notification(
            User receiver,
            Workspace workspace,
            NotificationType type,
            String title,
            String message,
            String targetUrl
    ) {
        this.receiver = receiver;
        this.workspace = workspace;
        this.type = type;
        this.title = title;
        this.message = message;
        this.targetUrl = targetUrl;
        this.read = false;
    }

    public void markAsRead() {
        if (!this.read) {
            this.read = true;
            this.readAt = LocalDateTime.now();
        }
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}