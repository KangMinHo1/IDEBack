package com.myide.backend.controller;

import com.myide.backend.dto.design.DesignDocumentRequest;
import com.myide.backend.dto.design.DesignDocumentResponse;
import com.myide.backend.service.DesignDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class DesignDocumentController {

    private final DesignDocumentService designDocumentService;

    @GetMapping("/workspaces/{workspaceId}/design/document")
    public DesignDocumentResponse getDesignDocument(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId
    ) {
        return designDocumentService.getDesignDocument(workspaceId, userId);
    }

    @PutMapping("/workspaces/{workspaceId}/design/document")
    public DesignDocumentResponse saveDesignDocument(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId,
            @RequestBody DesignDocumentRequest request
    ) {
        return designDocumentService.saveDesignDocument(workspaceId, userId, request);
    }
}