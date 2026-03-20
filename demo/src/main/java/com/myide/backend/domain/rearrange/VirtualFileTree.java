// 경로: src/main/java/com/myide/backend/domain/rearrange/VirtualFileTree.java
package com.myide.backend.domain.rearrange;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "virtual_file_tree", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_workspace_view_name",
                columnNames = {"workspaceId", "viewName"}
        )
})
public class VirtualFileTree {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String viewName; // 예: "풀스택 아키텍처별", "내 프롬프트 맞춤형"

    @Column(nullable = false)
    private String criteria; // AI에게 보냈던 프롬프트 원문

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String treeDataJson; // AI가 만들어준 가상 폴더 트리 (JSON)

    @Column(nullable = false)
    private boolean isActive; // 현재 워크스페이스에 적용 중인지 여부

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();


}