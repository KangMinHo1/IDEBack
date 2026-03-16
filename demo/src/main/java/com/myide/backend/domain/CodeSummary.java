package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "code_summary", uniqueConstraints = {
        // 💡 [수정] 동일한 파일에 대한 중복 저장을 막기 위한 스네이크 케이스 복합 유니크 제약 조건
        @UniqueConstraint(columnNames = {"workspace_id", "project_name", "branch_name", "file_path"})
})
public class CodeSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String projectName;

    @Column(nullable = false)
    private String branchName;

    @Column(nullable = false)
    private String filePath; // 예: src/main/java/com/.../Main.java

    // 파일 내용의 암호화된 해시값 (내용이 1글자라도 바뀌면 이 값이 달라짐)
    @Column(nullable = false)
    private String fileHash;

    // AI가 생성한 3~4줄 요약 내용 (길어질 수 있으니 TEXT 타입으로 지정)
    @Column(columnDefinition = "TEXT")
    private String summaryText;

    // 더티 체킹(Dirty Checking)을 위한 업데이트 메서드
    public void updateSummary(String newHash, String newSummaryText) {
        this.fileHash = newHash;
        this.summaryText = newSummaryText;
    }
}