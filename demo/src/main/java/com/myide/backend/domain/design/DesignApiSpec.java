package com.myide.backend.domain.design;

import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "design_api_specs")
public class DesignApiSpec {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 프론트/API에서 사용할 공개 ID
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_uuid", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, length = 20)
    private String method;

    @Column(nullable = false, length = 255)
    private String endpoint;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String request;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String response;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private DesignApiSpec(
            Workspace workspace,
            User createdBy,
            String method,
            String endpoint,
            String description,
            String request,
            String response
    ) {
        this.uuid = UUID.randomUUID().toString();
        this.workspace = workspace;
        this.createdBy = createdBy;
        this.method = method == null || method.isBlank() ? "GET" : method.toUpperCase();
        this.endpoint = endpoint;
        this.description = description;
        this.request = request;
        this.response = response;
    }

    public void update(
            String method,
            String endpoint,
            String description,
            String request,
            String response
    ) {
        this.method = method == null || method.isBlank() ? "GET" : method.toUpperCase();
        this.endpoint = endpoint;
        this.description = description;
        this.request = request;
        this.response = response;
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