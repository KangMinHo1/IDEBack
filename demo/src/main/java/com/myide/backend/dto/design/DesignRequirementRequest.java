package com.myide.backend.dto.design;

import jakarta.validation.constraints.NotBlank;

public record DesignRequirementRequest(
        String category,

        @NotBlank(message = "요구사항 이름은 필수입니다.")
        String name,

        String description
) {
}