package com.myide.backend.repository.design;

import com.myide.backend.domain.design.DesignDocSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DesignDocSnapshotRepository
        extends JpaRepository<DesignDocSnapshot, Long> {

    Optional<DesignDocSnapshot> findByWorkspace_Uuid(
            String workspaceUuid
    );

    boolean existsByWorkspace_Uuid(
            String workspaceUuid
    );

    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        DELETE FROM DesignDocSnapshot d
        WHERE d.workspace.uuid = :workspaceUuid
    """)
    void deleteAllByWorkspaceUuid(
            @Param("workspaceUuid") String workspaceUuid
    );
}