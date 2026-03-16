package com.myide.backend.dto.workspace;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WorkspaceMemberResponse {
    private Long userId;
    private String email;
    private String nickname;
    private String role; // OWNER, MEMBER 등
}