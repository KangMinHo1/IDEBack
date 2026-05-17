package com.myide.backend.dto.design;

import com.myide.backend.domain.design.DesignDocument;

import java.time.LocalDateTime;

public record DesignDocumentResponse(
        String id,
        String workspaceId,
        String erdNodesJson,
        String erdEdgesJson,
        String flowNodesJson,
        String flowEdgesJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static DesignDocumentResponse from(DesignDocument document) {
        return new DesignDocumentResponse(
                document.getUuid(),
                document.getWorkspace().getUuid(),
                document.getErdNodesJson(),
                document.getErdEdgesJson(),
                document.getFlowNodesJson(),
                document.getFlowEdgesJson(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    public static DesignDocumentResponse empty(String workspaceId) {
        return new DesignDocumentResponse(
                null,
                workspaceId,
                "[]",
                "[]",
                "[]",
                "[]",
                null,
                null
        );
    }
}