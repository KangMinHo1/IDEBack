package com.myide.backend.dto.workspace;

import com.myide.backend.domain.workspace.WorkspaceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WorkspaceCreateRequest {
    @NotBlank(message = "사용자 ID는 필수입니다.")
    private String userId;

    @NotBlank(message = "워크스페이스 이름은 필수입니다.")
    private String name;

    private String description;

    @NotBlank(message = "경로는 필수입니다.")
    private String path;

    @NotNull(message = "워크스페이스 타입은 필수입니다.")
    private WorkspaceType type;

    // 팀 모드일 때만 프론트에서 넘어옴
    private String teamName;
}