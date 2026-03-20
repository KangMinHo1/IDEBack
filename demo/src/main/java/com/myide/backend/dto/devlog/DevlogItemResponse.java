package com.myide.backend.dto.devlog;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DevlogItemResponse {
    private String id;
    private String title;
    private String date;
    private String summary;
    private String content;
    private List<String> tags;

    private String stage;
    private String goal;
    private String design;
    private String issue;
    private String solution;
    private String nextPlan;
    private String commitHash;
    private Integer progress;



}