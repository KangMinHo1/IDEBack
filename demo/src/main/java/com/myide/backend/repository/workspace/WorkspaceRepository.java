package com.myide.backend.repository.workspace;

import com.myide.backend.domain.workspace.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    // 💡 [수정] ownerId 대신 owner 객체의 id를 검색 (Long 타입)
    List<Workspace> findByOwner_Id(Long ownerId);

    // 💡 [수정] uuid와 owner 객체의 id를 함께 검색
    Optional<Workspace> findByUuidAndOwner_Id(String uuid, Long ownerId);

    // 💡 [수정] w.owner.id 로 변경하여 깔끔하게 Long 타입 하나만 받도록 쿼리 최적화
    @Query("SELECT DISTINCT w FROM Workspace w " +
            "LEFT JOIN WorkspaceMember wm ON w.uuid = wm.workspace.uuid " +
            "WHERE w.owner.id = :userId OR wm.user.id = :userId")
    List<Workspace> findMyAllWorkspaces(@Param("userId") Long userId);
}