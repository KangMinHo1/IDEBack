package com.myide.backend.controller;

import com.myide.backend.dto.aireport.FinalReportDraftRequest;
import com.myide.backend.dto.aireport.FinalReportDraftResponse;
import com.myide.backend.service.aireport.FinalReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/archive/final-report")
@RequiredArgsConstructor
public class FinalReportController {
    private final FinalReportService finalReportService;

    @PostMapping("/draft")
    public ResponseEntity<FinalReportDraftResponse> generateDraft(
            @PathVariable String workspaceId,
            @RequestBody FinalReportDraftRequest request
    ) {
        String draft = finalReportService.generateDraft(workspaceId, request);
        return ResponseEntity.ok(new FinalReportDraftResponse(draft));
    }
}
