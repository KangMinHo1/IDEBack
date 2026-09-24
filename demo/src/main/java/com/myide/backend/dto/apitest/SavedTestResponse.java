package com.myide.backend.dto.apitest;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavedTestResponse {

    private Long id;
    private String title;
    private String method;
    private String url;

    private List<TestParamDto> params;
    private List<TestParamDto> headers;

    private String body;
    private String bodyMode;
    private String rawType;

    private List<UrlEncodedItemDto> urlEncoded;

    private String authType;

    private String bearerToken;

    private String basicUsername;
    private String basicPassword;

    private String apiKeyName;
    private String apiKeyValue;
    private String apiKeyLocation;

    private List<AssertionDto> assertions;

    private String createdAt;
}
