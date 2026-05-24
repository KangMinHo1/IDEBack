package com.myide.backend.repository.webrtc;


import com.myide.backend.domain.webrtc.VoiceChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query("""
            select coalesce(max(vc.sortOrder), 0)
            from VoiceChannelEntity vc
            where vc.workspaceId = :workspaceId
              and vc.deleted = false
            """)
    Integer findMaxSortOrderByWorkspaceId(@Param("workspaceId") String workspaceId);
}