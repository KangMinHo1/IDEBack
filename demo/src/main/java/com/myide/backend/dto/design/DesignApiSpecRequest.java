package com.myide.backend.dto.design;

import jakarta.validation.constraints.NotBlank;

public record DesignApiSpecRequest(
        @NotBlank(message = "HTTP Method는 필수입니다.")
        String method,

        @NotBlank(message = "Endpoint는 필수입니다.")
        String endpoint,

        String description,

        String request,

        String response
) {
}