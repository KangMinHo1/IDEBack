// 경로: src/main/java/com/myide/backend/repository/rearrange/VirtualFileTreeRepository.java
package com.myide.backend.repository.rearrange;

import com.myide.backend.domain.rearrange.VirtualFileTree;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VirtualFileTreeRepository extends JpaRepository<VirtualFileTree, Long> {

    // 💡 [수정] 브랜치까지 묶어서 중복 검사
    boolean existsByWorkspaceIdAndBranchNameAndViewName(String workspaceId, String branchName, String viewName);

    // 💡 [수정] 현재 보고 있는 브랜치에 저장된 뷰 목록만 가져오기
    List<VirtualFileTree> findByWorkspaceIdAndBranchNameOrderByCreatedAtDesc(String workspaceId, String branchName);

    // 💡 [수정] 현재 보고 있는 브랜치에서 Active 상태인 뷰 가져오기
    Optional<VirtualFileTree> findByWorkspaceIdAndBranchNameAndIsActiveTrue(String workspaceId, String branchName);

    // 💡 [수정] '특정 브랜치'의 모든 뷰만 콕 집어서 비활성화
    @Modifying
    @Query("UPDATE VirtualFileTree v SET v.isActive = false WHERE v.workspaceId = :workspaceId AND v.branchName = :branchName")
    void deactivateAllByWorkspaceIdAndBranchName(@Param("workspaceId") String workspaceId, @Param("branchName") String branchName);
}