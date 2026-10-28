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
        name = "design_documents",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_design_document_workspace",
                        columnNames = {"workspace_uuid"}
                )
        }
)
public class DesignDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 프론트/API에서 사용하는 공개 식별자
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String uuid;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_uuid", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Lob
    @Column(name = "erd_nodes_json", nullable = false, columnDefinition = "LONGTEXT")
    private String erdNodesJson;

    @Lob
    @Column(name = "erd_edges_json", nullable = false, columnDefinition = "LONGTEXT")
    private String erdEdgesJson;

    @Lob
    @Column(name = "flow_nodes_json", nullable = false, columnDefinition = "LONGTEXT")
    private String flowNodesJson;

    @Lob
    @Column(name = "flow_edges_json", nullable = false, columnDefinition = "LONGTEXT")
    private String flowEdgesJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private DesignDocument(
            Workspace workspace,
            User createdBy,
            String erdNodesJson,
            String erdEdgesJson,
            String flowNodesJson,
            String flowEdgesJson
    ) {
        this.uuid = UUID.randomUUID().toString();
        this.workspace = workspace;
        this.createdBy = createdBy;
        this.erdNodesJson = normalizeJsonArray(erdNodesJson);
        this.erdEdgesJson = normalizeJsonArray(erdEdgesJson);
        this.flowNodesJson = normalizeJsonArray(flowNodesJson);
        this.flowEdgesJson = normalizeJsonArray(flowEdgesJson);
    }

    public void update(
            String erdNodesJson,
            String erdEdgesJson,
            String flowNodesJson,
            String flowEdgesJson
    ) {
        this.erdNodesJson = normalizeJsonArray(erdNodesJson);
        this.erdEdgesJson = normalizeJsonArray(erdEdgesJson);
        this.flowNodesJson = normalizeJsonArray(flowNodesJson);
        this.flowEdgesJson = normalizeJsonArray(flowEdgesJson);
    }

    /** 프로젝트 복제용 사본. 공개 uuid 는 새로 받는다. */
    public DesignDocument duplicateTo(Workspace targetWorkspace) {
        DesignDocument copy = new DesignDocument();

        copy.uuid = UUID.randomUUID().toString();
        copy.workspace = targetWorkspace;
        copy.createdBy = this.createdBy;
        copy.erdNodesJson = this.erdNodesJson;
        copy.erdEdgesJson = this.erdEdgesJson;
        copy.flowNodesJson = this.flowNodesJson;
        copy.flowEdgesJson = this.flowEdgesJson;
        copy.createdAt = this.createdAt;
        copy.updatedAt = this.updatedAt;

        return copy;
    }

    private static String normalizeJsonArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
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

        this.erdNodesJson = normalizeJsonArray(this.erdNodesJson);
        this.erdEdgesJson = normalizeJsonArray(this.erdEdgesJson);
        this.flowNodesJson = normalizeJsonArray(this.flowNodesJson);
        this.flowEdgesJson = normalizeJsonArray(this.flowEdgesJson);
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}