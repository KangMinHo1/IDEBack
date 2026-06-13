package com.myide.backend.repository.webrtc;

import com.myide.backend.domain.webrtc.VoiceChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VoiceChannelRepository extends JpaRepository<VoiceChannelEntity, Long> {

    List<VoiceChannelEntity> findByWorkspaceIdAndDeletedFalseOrderBySortOrderAscCreatedAtAsc(
            String workspaceId
    );

    Optional<VoiceChannelEntity> findByWorkspaceIdAndChannelId(
            String workspaceId,
            String channelId
    );

    Optional<VoiceChannelEntity> findByWorkspaceIdAndChannelIdAndDeletedFalse(
            String workspaceId,
            String channelId
    );

    boolean existsByWorkspaceIdAndChannelId(
            String workspaceId,
            String channelId
    );

    boolean existsByWorkspaceIdAndChannelIdAndDeletedFalse(
            String workspaceId,
            String channelId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO voice_channels (
                channel_id,
                created_at,
                created_by,
                deleted,
                deleted_at,
                icon,
                name,
                sort_order,
                workspace_id
            )
            VALUES (
                :channelId,
                CURRENT_TIMESTAMP,
                NULL,
                false,
                NULL,
                :icon,
                :name,
                0,
                :workspaceId
            )
            ON DUPLICATE KEY UPDATE
                name = CASE
                    WHEN deleted = true THEN VALUES(name)
                    ELSE name
                END,
                icon = CASE
                    WHEN deleted = true THEN VALUES(icon)
                    ELSE icon
                END,
                sort_order = CASE
                    WHEN deleted = true THEN 0
                    ELSE sort_order
                END,
                deleted_at = NULL,
                deleted = false
            """, nativeQuery = true)
    int upsertDefaultChannel(
            @Param("workspaceId") String workspaceId,
            @Param("channelId") String channelId,
            @Param("name") String name,
            @Param("icon") String icon
    );

    @Query("""
            select coalesce(max(vc.sortOrder), 0)
            from VoiceChannelEntity vc
            where vc.workspaceId = :workspaceId
              and vc.deleted = false
            """)
    Integer findMaxSortOrderByWorkspaceId(@Param("workspaceId") String workspaceId);
}