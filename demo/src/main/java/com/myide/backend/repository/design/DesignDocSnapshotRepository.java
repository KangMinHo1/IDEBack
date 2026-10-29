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

    /**
     * 버전만 읽는다. LONGTEXT 두 칸(Yjs 바이너리, 평문 사본)을 건드리지 않아
     * 최종 보고서가 요청마다 캐시가 유효한지 확인하는 데 드는 비용이 작다.
     */
    @Query("""
        SELECT new com.myide.backend.repository.design.DesignSnapshotVersion(d.id, d.revision, d.updatedAt)
        FROM DesignDocSnapshot d
        WHERE d.workspace.uuid = :workspaceUuid
    """)
    Optional<DesignSnapshotVersion> findVersionByWorkspaceUuid(
            @Param("workspaceUuid") String workspaceUuid
    );

    /**
     * 평문 사본만 읽는다. 최종 보고서는 Yjs 바이너리를 쓰지 않는데,
     * findByWorkspace_Uuid 는 수 MB 가 될 수 있는 그 칸까지 함께 읽는다.
     */
    @Query("""
        SELECT d.projectionJson
        FROM DesignDocSnapshot d
        WHERE d.workspace.uuid = :workspaceUuid
    """)
    Optional<String> findProjectionJsonByWorkspaceUuid(
            @Param("workspaceUuid") String workspaceUuid
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