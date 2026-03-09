package com.myide.backend.dto.devlog;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ProjectDevlogGroupResponse {
    private Long id;
    private String name;
    private String description;
    private String language;
    private String lastUpdatedDate;
    private int devlogCount;
    private List<DevlogItemResponse> posts;
}