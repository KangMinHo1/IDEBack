package com.myide.backend.dto.apitest;

import lombok.*;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HistoryRequest {

    private String method;
    private String url;

    private Integer status;
    private String statusText;

    private Boolean success;
    private Long durationMs;

    private Long responseSize;

    private String responseBody;

    private Map<String, String> responseHeaders;
}
