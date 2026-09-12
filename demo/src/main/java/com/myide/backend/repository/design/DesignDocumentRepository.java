package com.myide.backend.repository.design;

import com.myide.backend.domain.design.DesignDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DesignDocumentRepository
        extends JpaRepository<DesignDocument, Long> {

    Optional<DesignDocument> findByWorkspace_Uuid(
            String workspaceUuid
    );

    Optional<DesignDocument> findByUuid(
            String uuid
    );

    boolean existsByWorkspace_Uuid(
            String workspaceUuid
    );

    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        DELETE FROM DesignDocument d
        WHERE d.workspace.uuid = :workspaceUuid
    """)
    void deleteAllByWorkspaceUuid(
            @Param("workspaceUuid") String workspaceUuid
    );
}