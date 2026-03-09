package com.myide.backend.dto.devlog;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DevlogItemResponse {
    private Long id;
    private String title;
    private String date;
    private String summary;
    private List<String> tags;
}