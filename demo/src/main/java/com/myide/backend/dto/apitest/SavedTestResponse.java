package com.myide.backend.dto.apitest;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SavedTestResponse {
    private Long id;
    private String title;
    private String method;
    private String url;
    private List<TestParamDto> params;
    private List<TestParamDto> headers;
    private String body;

    private String createdAt; // ISO string
}