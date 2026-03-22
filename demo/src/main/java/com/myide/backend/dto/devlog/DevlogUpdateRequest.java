package com.myide.backend.dto.devlog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

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
    private LocalDate date;

    private String stage;
    private String goal;
    private String design;
    private String issue;
    private String solution;
    private String nextPlan;
    private String commitHash;
    private Integer progress;
}