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

    /* uuid 는 워크스페이스 안에서만 유일하다. 이유는 DesignRequirementRepository 참고. */
    Optional<DesignApiSpec> findByUuidAndWorkspace_Uuid(
            String uuid,
            String workspaceUuid
    );

    /* workspaceId 없이 들어오는 옛 경로(/api/design/apis/{id}) 호환용. */
    List<DesignApiSpec> findAllByUuid(String uuid);

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