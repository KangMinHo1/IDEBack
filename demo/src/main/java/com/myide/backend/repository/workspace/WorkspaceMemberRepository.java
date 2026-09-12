package com.myide.backend.repository.workspace;

import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository
        extends JpaRepository<WorkspaceMember, Long> {

    List<WorkspaceMember> findByUser_Id(Long userId);

    Optional<WorkspaceMember> findByWorkspace_UuidAndUser_Id(
            String workspaceUuid,
            Long userId
    );

    boolean existsByWorkspaceAndUser(
            Workspace workspace,
            User user
    );

    List<WorkspaceMember> findByUser_IdAndStatus(
            Long userId,
            WorkspaceMember.JoinStatus status
    );

    List<WorkspaceMember> findByWorkspace_UuidAndStatus(
            String workspaceId,
            WorkspaceMember.JoinStatus status
    );

    boolean existsByWorkspace_UuidAndUser_IdAndStatus(
            String workspaceId,
            Long userId,
            WorkspaceMember.JoinStatus status
    );

    /*
     * 워크스페이스 삭제 전에
     * 해당 워크스페이스의 OWNER / MEMBER 레코드를 모두 삭제
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM WorkspaceMember wm
        WHERE wm.workspace.uuid = :workspaceId
    """)
    void deleteAllByWorkspaceId(
            @Param("workspaceId") String workspaceId
    );
}