package com.myide.backend.dto.apitest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class ProxyRequest {

    @NotBlank
    private String url;

    @NotBlank
    private String method;

    private Map<String, String> headers;

    // none | raw | form-data | x-www-form-urlencoded
    private String bodyType;

    // json | text | html | xml
    private String rawType;

    // raw / x-www-form-urlencoded 본문
    private String body;

    @Valid
    private List<FormDataPart> formData;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class FormDataPart {

        @NotBlank
        private String key;

        // text | file
        @NotBlank
        private String type;

        private String value;

        private String fileName;
        private String contentType;
        private String base64;
    }
}
