package com.myide.backend.dto.design;

import com.myide.backend.domain.design.DesignApiSpec;

import java.time.LocalDateTime;

public record DesignApiSpecResponse(
        String id,
        String workspaceId,
        String method,
        String endpoint,
        String description,
        String request,
        String response,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static DesignApiSpecResponse from(DesignApiSpec apiSpec) {
        return new DesignApiSpecResponse(
                apiSpec.getUuid(),
                apiSpec.getWorkspace().getUuid(),
                apiSpec.getMethod(),
                apiSpec.getEndpoint(),
                apiSpec.getDescription(),
                apiSpec.getRequest(),
                apiSpec.getResponse(),
                apiSpec.getCreatedAt(),
                apiSpec.getUpdatedAt()
        );
    }
}