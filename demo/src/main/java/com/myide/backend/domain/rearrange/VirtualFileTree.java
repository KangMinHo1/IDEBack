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
                name = "uk_workspace_branch_view_name",
                // 💡 [NEW] 같은 워크스페이스라도 '브랜치'가 다르면 같은 이름 허용!
                columnNames = {"workspaceId", "branchName", "viewName"}
        )
})
public class VirtualFileTree {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String workspaceId;

    // 💡 [NEW] 이 가상 뷰가 어느 브랜치에 종속된 것인지 기록합니다.
    @Column(nullable = false)
    private String branchName;

    @Column(nullable = false)
    private String viewName;

    @Column(nullable = false)
    private String criteria;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String treeDataJson;

    @Column(nullable = false)
    private boolean isActive;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}