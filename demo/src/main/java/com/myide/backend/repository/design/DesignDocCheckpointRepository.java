package com.myide.backend.repository.design;

import com.myide.backend.domain.design.DesignDocCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DesignDocCheckpointRepository
        extends JpaRepository<DesignDocCheckpoint, Long> {

    List<DesignDocCheckpoint>
    findByWorkspace_UuidOrderByCreatedAtDesc(
            String workspaceUuid
    );

    Optional<DesignDocCheckpoint>
    findByUuidAndWorkspace_Uuid(
            String uuid,
            String workspaceUuid
    );

    Optional<DesignDocCheckpoint>
    findFirstByWorkspace_UuidOrderByCreatedAtDesc(
            String workspaceUuid
    );

    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        DELETE FROM DesignDocCheckpoint d
        WHERE d.workspace.uuid = :workspaceUuid
    """)
    void deleteAllByWorkspaceUuid(
            @Param("workspaceUuid") String workspaceUuid
    );
}