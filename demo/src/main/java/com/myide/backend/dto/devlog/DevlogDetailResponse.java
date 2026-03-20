package com.myide.backend.dto.devlog;

import lombok.Builder;
import lombok.Getter;


import java.util.List;

@Getter
@Builder
public class DevlogDetailResponse {
    private Long id;
    private String workspaceId;
    private Long projectId;
    private String title;
    private String date;
    private String summary;
    private String content;
    private List<String> tags;

    /**
     * 새 필드
     */
    private String stage;
    private String goal;
    private String design;
    private String issue;
    private String solution;
    private String nextPlan;
    private String commitHash;
    private Integer progress;



}