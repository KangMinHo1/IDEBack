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

    private static String normalizeJsonArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;

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