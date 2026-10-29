package com.myide.backend.domain.design;

import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 되돌리기 안전망.
 *
 * AI 초안을 적용하거나 코드를 생성하기 직전처럼 문서가 크게 바뀌는
 * 순간에 자동으로 한 벌 떠 둔다. AI가 엉뚱한 걸 잔뜩 집어넣었을 때
 * 팀 전체가 손으로 지우지 않아도 되게 하기 위한 것이다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "design_doc_checkpoints")
public class DesignDocCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_uuid", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 120)
    private String label;

    /**
     * 그 시점에 문서에 무엇이 담겨 있었는지 한 줄로.
     *
     * 라벨만으로는 "복원 직전 자동 보관"이 여러 줄 쌓였을 때 어느 것으로
     * 되돌려야 할지 알 수 없다. "요구사항 12 · 화면 7 · 표 5 · API 20"처럼
     * 담긴 것을 적어 두면 목록만 보고 고를 수 있다.
     *
     * 예전에 만들어진 기록에는 없으므로 비어 있을 수 있다.
     */
    @Column(length = 200)
    private String summary;

    @Lob
    @Column(name = "yjs_update_base64", nullable = false, columnDefinition = "LONGTEXT")
    private String yjsUpdateBase64;

    @Lob
    @Column(name = "projection_json", nullable = false, columnDefinition = "LONGTEXT")
    private String projectionJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private DesignDocCheckpoint(Workspace workspace,
                               String label,
                               String summary,
                               String yjsUpdateBase64,
                               String projectionJson,
                               User createdBy) {
        this.uuid = UUID.randomUUID().toString();
        this.workspace = workspace;
        this.label = label;
        this.summary = summary;
        this.yjsUpdateBase64 = yjsUpdateBase64;
        this.projectionJson = projectionJson;
        this.createdBy = createdBy;
    }

    /**
     * 프로젝트 복제용 사본.
     *
     * 목록과 "가장 최근 기록" 판정이 createdAt 순이라, 시각을 새로 찍으면 한 번에
     * 넣은 기록들의 순서가 뒤섞인다. 그래서 원본 시각을 그대로 옮긴다.
     */
    public DesignDocCheckpoint duplicateTo(Workspace targetWorkspace) {
        DesignDocCheckpoint copy = new DesignDocCheckpoint(
                targetWorkspace,
                this.label,
                this.summary,
                this.yjsUpdateBase64,
                this.projectionJson,
                this.createdBy
        );

        copy.createdAt = this.createdAt;

        return copy;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
