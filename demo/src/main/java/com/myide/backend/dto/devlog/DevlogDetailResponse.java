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
}