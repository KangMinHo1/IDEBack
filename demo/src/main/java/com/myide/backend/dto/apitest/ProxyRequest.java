package com.myide.backend.dto.apitest;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.Map;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ProxyRequest {

    @NotBlank
    private String url;

    @NotBlank
    private String method; // GET/POST/PUT/DELETE

    // key-value headers
    private Map<String, String> headers;

    // raw body string (JSON 등)
    private String body;
}