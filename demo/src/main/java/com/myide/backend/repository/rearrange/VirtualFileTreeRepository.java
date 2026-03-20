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

    // 👇 중복 검사를 위한 메서드 추가!
    boolean existsByWorkspaceIdAndViewName(String workspaceId, String viewName);

    List<VirtualFileTree> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    Optional<VirtualFileTree> findByWorkspaceIdAndIsActiveTrue(String workspaceId);

    // 특정 워크스페이스의 모든 뷰를 비활성화 (새로운 뷰를 Active 하기 전 호출)
    @Modifying
    @Query("UPDATE VirtualFileTree v SET v.isActive = false WHERE v.workspaceId = :workspaceId")
    void deactivateAllByWorkspaceId(@Param("workspaceId") String workspaceId);
}