package com.myide.backend.dto.presence;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PresenceMessage {

    private PresenceType type;

    private String workspaceId;

    private Long userId;

    private String nickname;

    private String email;

    private String profileImageUrl;

    private List<PresenceMember> members;

    private String errorMessage;
}