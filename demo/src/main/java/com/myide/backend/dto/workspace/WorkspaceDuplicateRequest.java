package com.myide.backend.dto.workspace;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WorkspaceDuplicateRequest {

    @NotBlank(message = "복제할 워크스페이스 이름은 필수입니다.")
    private String name;

    private String description;
}