package com.myide.backend.dto.webrtc;

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
public class VoiceRoomMember {

    private Long userId;

    private String nickname;

    private String workspaceId;

    private String channelId;

    private String sessionId;

    private boolean muted;

    private Instant joinedAt;
}