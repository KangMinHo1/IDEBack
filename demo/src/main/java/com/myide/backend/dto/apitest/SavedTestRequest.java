package com.myide.backend.dto.apitest;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavedTestRequest {

    private String title;
    private String method;
    private String url;

    private List<TestParamDto> params;
    private List<TestParamDto> headers;

    private String body;

    // none | raw | form-data | x-www-form-urlencoded
    private String bodyMode;

    // json | text | html | xml
    private String rawType;

    private List<UrlEncodedItemDto> urlEncoded;

    // none | bearer | basic | api-key
    private String authType;

    private String bearerToken;

    private String basicUsername;
    private String basicPassword;

    private String apiKeyName;
    private String apiKeyValue;

    // header | query
    private String apiKeyLocation;

    private List<AssertionDto> assertions;
}
