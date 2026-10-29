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
@Table(
        name = "design_api_specs",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_design_api_specs_workspace_uuid",
                        columnNames = {"workspace_uuid", "uuid"}
                )
        }
)
public class DesignApiSpec {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 프론트/API에서 사용할 공개 ID
    // 워크스페이스 안에서만 유일하다. 이유는 DesignRequirement.uuid 와 같다(복제 시 Yjs 항목 id 공유).
    @Column(nullable = false, updatable = false, length = 36)
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
            String uuid,
            Workspace workspace,
            User createdBy,
            String method,
            String endpoint,
            String description,
            String request,
            String response
    ) {
        // uuid 를 받는 이유는 DesignRequirement 와 같다(역투영 시 제자리 갱신).
        this.uuid = uuid == null || uuid.isBlank() ? UUID.randomUUID().toString() : uuid;
        this.workspace = workspace;
        this.createdBy = createdBy;
        this.method = method == null || method.isBlank() ? "GET" : method.toUpperCase();
        this.endpoint = endpoint;
        this.description = description;
        this.request = request;
        this.response = response;
    }

    /** 프로젝트 복제용 사본. uuid 를 그대로 두는 이유는 DesignRequirement.duplicateTo 와 같다. */
    public DesignApiSpec duplicateTo(Workspace targetWorkspace) {
        DesignApiSpec copy = new DesignApiSpec();

        copy.uuid = this.uuid;
        copy.workspace = targetWorkspace;
        copy.createdBy = this.createdBy;
        copy.method = this.method;
        copy.endpoint = this.endpoint;
        copy.description = this.description;
        copy.request = this.request;
        copy.response = this.response;
        copy.createdAt = this.createdAt;
        copy.updatedAt = this.updatedAt;

        return copy;
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

        // 복제본은 원본 시각을 들고 온다. 목록이 createdAt 순이라 순서를 지키려면 필요하다.
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