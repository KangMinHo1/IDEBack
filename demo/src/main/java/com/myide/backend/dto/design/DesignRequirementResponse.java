package com.myide.backend.dto.design;

import com.myide.backend.domain.design.DesignRequirement;

import java.time.LocalDateTime;

public record DesignRequirementResponse(
        String id,
        String workspaceId,
        String category,
        String name,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static DesignRequirementResponse from(DesignRequirement requirement) {
        return new DesignRequirementResponse(
                requirement.getUuid(),
                requirement.getWorkspace().getUuid(),
                requirement.getCategory(),
                requirement.getName(),
                requirement.getDescription(),
                requirement.getCreatedAt(),
                requirement.getUpdatedAt()
        );
    }
}