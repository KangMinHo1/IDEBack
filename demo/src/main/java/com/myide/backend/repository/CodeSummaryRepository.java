package com.myide.backend.repository;

import com.myide.backend.domain.CodeSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CodeSummaryRepository extends JpaRepository<CodeSummary, Long> {

    // 특정 워크스페이스 -> 프로젝트 -> 브랜치 -> 파일경로에 해당하는 요약본 찾기
    Optional<CodeSummary> findByWorkspaceIdAndProjectNameAndBranchNameAndFilePath(
            String workspaceId,
            String projectName,
            String branchName,
            String filePath
    );
}