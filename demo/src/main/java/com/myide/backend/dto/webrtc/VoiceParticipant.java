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
public class VoiceParticipant {

    private Long userId;

    private String nickname;

    private String channelId;

    private boolean muted;

    private Instant joinedAt;
}