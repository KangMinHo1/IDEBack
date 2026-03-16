package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "code_map_cache", uniqueConstraints = {
        // 💡 [수정] DB 물리 컬럼명인 스네이크 케이스로 변경 (서버 기동 에러 완벽 해결)
        @UniqueConstraint(columnNames = {"workspace_id", "project_name", "branch_name"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeMapCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String projectName;

    @Column(nullable = false)
    private String branchName;

    // 파싱된 노드와 엣지 정보를 통째로 JSON 형태로 저장 (크기가 클 수 있으므로 LONGTEXT 사용)
    @Column(columnDefinition = "LONGTEXT")
    private String mapDataJson;
}