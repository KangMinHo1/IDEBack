package com.myide.backend.repository.codemap;

import com.myide.backend.domain.CodeMapCache;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CodeMapCacheRepository extends JpaRepository<CodeMapCache, Long> {
    Optional<CodeMapCache> findByWorkspaceIdAndProjectNameAndBranchName(String workspaceId, String projectName, String branchName);

    // 특정 프로젝트의 캐시를 무효화(삭제)할 때 사용
    void deleteByWorkspaceIdAndProjectNameAndBranchName(String workspaceId, String projectName, String branchName);
}