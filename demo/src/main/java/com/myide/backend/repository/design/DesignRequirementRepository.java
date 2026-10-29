package com.myide.backend.repository.design;

import com.myide.backend.domain.design.DesignRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DesignRequirementRepository
        extends JpaRepository<DesignRequirement, Long> {

    /*
     * uuid 는 워크스페이스 안에서만 유일하다(복제한 프로젝트는 원본과 같은 uuid 를 갖는다).
     * 단건 조회는 반드시 워크스페이스와 함께 한다.
     */
    Optional<DesignRequirement> findByUuidAndWorkspace_Uuid(
            String uuid,
            String workspaceUuid
    );

    /* workspaceId 없이 들어오는 옛 경로(/api/design/requirements/{id}) 호환용. */
    List<DesignRequirement> findAllByUuid(
            String uuid
    );

    List<DesignRequirement>
    findByWorkspace_UuidOrderByCreatedAtAsc(
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
        DELETE FROM DesignRequirement d
        WHERE d.workspace.uuid = :workspaceUuid
    """)
    void deleteAllByWorkspaceUuid(
            @Param("workspaceUuid") String workspaceUuid
    );
}