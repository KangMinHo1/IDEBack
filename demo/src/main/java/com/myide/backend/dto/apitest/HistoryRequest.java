package com.myide.backend.dto.apitest;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class HistoryRequest {
    private String method;
    private String url;
    private Integer status;
    private Boolean success;
    private Long durationMs;
}