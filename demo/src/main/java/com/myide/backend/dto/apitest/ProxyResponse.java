package com.myide.backend.dto.apitest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
@AllArgsConstructor
public class ProxyResponse {

    private int status;
    private String statusText;
    private Object data;
    private long timeMs;
    private Map<String, String> headers;
    private long size;
}
