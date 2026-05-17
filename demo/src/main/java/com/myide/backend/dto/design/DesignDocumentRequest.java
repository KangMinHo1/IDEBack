package com.myide.backend.dto.design;

public record DesignDocumentRequest(
        String erdNodesJson,
        String erdEdgesJson,
        String flowNodesJson,
        String flowEdgesJson
) {
}