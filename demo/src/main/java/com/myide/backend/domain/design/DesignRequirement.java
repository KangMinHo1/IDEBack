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
        name = "design_requirements",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_design_requirements_workspace_uuid",
                        columnNames = {"workspace_uuid", "uuid"}
                )
        }
)
public class DesignRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 프론트/API에서 사용할 공개 ID
    //
    // 워크스페이스 안에서만 유일하다. 이 값은 설계 v2 문서(Yjs)의 항목 id 라서,
    // 프로젝트를 복제하면 원본과 사본이 같은 id 를 갖는다. 전역 UNIQUE 로 두면
    // 사본이 자동 저장(역투영)될 때 원본 행과 부딪혀 저장이 실패한다.
    // 기존 DB 의 전역 UNIQUE 인덱스는 ddl-auto 가 지우지 않으므로
    // docs/migrations/2026-10-27-design-uuid-scope.sql 로 직접 내려야 한다.
    @Column(nullable = false, updatable = false, length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_uuid", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private DesignRequirement(
            String uuid,
            Workspace workspace,
            User createdBy,
            String category,
            String name,
            String description
    ) {
        // uuid 를 받는 이유: 설계 v2 문서를 예전 형식으로 되쓸 때(역투영)
        // 문서의 항목 id 를 그대로 uuid 로 써야 행을 제자리에서 갱신할 수 있다.
        // 안 받으면 자동 저장이 돌 때마다 행 전체를 지웠다 다시 넣게 된다.
        this.uuid = uuid == null || uuid.isBlank() ? UUID.randomUUID().toString() : uuid;
        this.workspace = workspace;
        this.createdBy = createdBy;
        this.category = category == null || category.isBlank() ? "기본" : category;
        this.name = name;
        this.description = description;
    }

    /**
     * 프로젝트 복제용 사본. uuid 는 일부러 그대로 둔다 — 복제된 Yjs 문서의 항목 id 와
     * 같아야 사본의 첫 자동 저장이 이 행을 제자리에서 갱신한다(새 id 를 주면 전부
     * 지웠다 다시 넣게 된다). 원본과의 충돌은 (workspace_uuid, uuid) 범위로 막는다.
     */
    public DesignRequirement duplicateTo(Workspace targetWorkspace) {
        DesignRequirement copy = new DesignRequirement();

        copy.uuid = this.uuid;
        copy.workspace = targetWorkspace;
        copy.createdBy = this.createdBy;
        copy.category = this.category;
        copy.name = this.name;
        copy.description = this.description;
        copy.createdAt = this.createdAt;
        copy.updatedAt = this.updatedAt;

        return copy;
    }

    public void update(String category, String name, String description) {
        this.category = category == null || category.isBlank() ? "기본" : category;
        this.name = name;
        this.description = description;
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