package com.myide.backend.domain.webrtc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "voice_channels",
        indexes = {
                @Index(name = "idx_voice_channels_workspace", columnList = "workspace_id"),
                @Index(name = "idx_voice_channels_workspace_deleted", columnList = "workspace_id, deleted")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_voice_channels_workspace_channel",
                        columnNames = {"workspace_id", "channel_id"}
                )
        }
)
public class VoiceChannelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * WebRTC signaling에서는 workspaceId를 String으로 받고 있으므로
     * DB에도 문자열로 저장합니다.
     *
     * 실제 workspace PK가 Long이어도 destination variable에서 넘어오는 값이
     * "1", "2" 형태면 그대로 저장됩니다.
     */
    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    /*
     * 클라이언트와 WebRTC signaling에서 사용하는 채널 식별자.
     * 기본 채널은 항상 "general"입니다.
     */
    @Column(name = "channel_id", nullable = false, length = 80)
    private String channelId;

    @Column(name = "name", nullable = false, length = 40)
    private String name;

    @Column(name = "icon", nullable = false, length = 12)
    private String icon;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }

        if (sortOrder == null) {
            sortOrder = 0;
        }
    }
}