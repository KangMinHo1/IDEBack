package com.myide.backend.repository.design;

import com.myide.backend.domain.design.DesignApiSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DesignApiSpecRepository
        extends JpaRepository<DesignApiSpec, Long> {

    Optional<DesignApiSpec> findByUuid(String uuid);

    List<DesignApiSpec> findByWorkspace_UuidOrderByCreatedAtAsc(
            String workspaceUuid
    );

    boolean existsByUuidAndWorkspace_Uuid(
            String uuid,
            String workspaceUuid
    );

    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        DELETE FROM DesignApiSpec d
        WHERE d.workspace.uuid = :workspaceUuid
    """)
    void deleteAllByWorkspaceUuid(
            @Param("workspaceUuid") String workspaceUuid
    );
} 