package com.myide.backend.dto.apitest;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DiscoveredEndpointResponse {
    private String id;
    private String method;
    private String path;
    private String projectName;
    private String filePath;
    private String controller;
    private String handler;
}
