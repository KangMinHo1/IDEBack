package com.myide.backend.repository.workspace;

import com.myide.backend.domain.workspace.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    // ownerId 대신 owner 객체의 id를 검색 (Long 타입)
    List<Workspace> findByOwner_Id(Long ownerId);

    // uuid와 owner 객체의 id를 함께 검색
    Optional<Workspace> findByUuidAndOwner_Id(String uuid, Long ownerId);

    // 💡 [버그 수정 2] 멤버 상태(status)가 'ACCEPTED'인 사람만 목록에 표시하도록 조건 추가!
    @Query("SELECT DISTINCT w FROM Workspace w " +
            "LEFT JOIN WorkspaceMember wm ON w.uuid = wm.workspace.uuid " +
            "WHERE w.owner.id = :userId OR (wm.user.id = :userId AND wm.status = 'ACCEPTED')")
    List<Workspace> findMyAllWorkspaces(@Param("userId") Long userId);
}