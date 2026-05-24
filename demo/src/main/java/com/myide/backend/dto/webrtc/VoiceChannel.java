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
public class VoiceChannel {

    private String channelId;

    private String name;

    private String icon;

    private Long createdBy;

    private Instant createdAt;

    private Integer sortOrder;

    private boolean defaultChannel;
}