package com.myide.backend.dto.devlog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DevlogUpdateRequest {

    @NotBlank
    private String workspaceId;

    @NotNull
    private Long projectId;

    @NotBlank
    private String title;

    @NotBlank
    private String summary;

    @NotBlank
    private String content;

    private String tagsText;
}