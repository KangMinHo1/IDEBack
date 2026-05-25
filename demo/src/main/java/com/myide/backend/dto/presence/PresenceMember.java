package com.myide.backend.dto.presence;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PresenceMember {

    private Long userId;

    private String nickname;

    private String email;

    private String profileImageUrl;

    private String sessionId;

    private Instant joinedAt;

    private Instant lastSeenAt;
}