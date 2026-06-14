package com.myide.backend.repository;

import com.myide.backend.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByWorkspaceUuidOrderByUpdatedAtDesc(String workspaceUuid);

    Optional<Project> findByIdAndWorkspaceUuid(Long id, String workspaceUuid);


    @Query(value = """
        SELECT DISTINCT p.git_url
        FROM project p
        JOIN workspace w ON w.uuid = p.workspace_id
        LEFT JOIN workspace_members wm ON wm.workspace_id = w.uuid
        WHERE (
            w.owner_id = :userId
            OR (
                wm.user_id = :userId
                AND wm.status = 'ACCEPTED'
            )
        )
          AND p.git_url IS NOT NULL
          AND TRIM(p.git_url) <> ''
    """, nativeQuery = true)
    List<String> findLinkedGitUrlsByOwnerOrAcceptedMember(
            @Param("userId") Long userId
    );
}