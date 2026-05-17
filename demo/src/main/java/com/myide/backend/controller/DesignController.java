package com.myide.backend.controller;

import com.myide.backend.dto.design.DesignApiSpecRequest;
import com.myide.backend.dto.design.DesignApiSpecResponse;
import com.myide.backend.dto.design.DesignRequirementRequest;
import com.myide.backend.dto.design.DesignRequirementResponse;
import com.myide.backend.service.DesignService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class DesignController {

    private final DesignService designService;

    @GetMapping("/workspaces/{workspaceId}/design/requirements")
    public List<DesignRequirementResponse> getRequirements(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId
    ) {
        return designService.getRequirements(workspaceId, userId);
    }

    @PostMapping("/workspaces/{workspaceId}/design/requirements")
    public DesignRequirementResponse createRequirement(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DesignRequirementRequest request
    ) {
        return designService.createRequirement(workspaceId, userId, request);
    }

    @PatchMapping("/design/requirements/{requirementId}")
    public DesignRequirementResponse updateRequirement(
            @PathVariable String requirementId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DesignRequirementRequest request
    ) {
        return designService.updateRequirement(requirementId, userId, request);
    }

    @DeleteMapping("/design/requirements/{requirementId}")
    public void deleteRequirement(
            @PathVariable String requirementId,
            @AuthenticationPrincipal Long userId
    ) {
        designService.deleteRequirement(requirementId, userId);
    }

    @GetMapping("/workspaces/{workspaceId}/design/apis")
    public List<DesignApiSpecResponse> getApiSpecs(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId
    ) {
        return designService.getApiSpecs(workspaceId, userId);
    }

    @PostMapping("/workspaces/{workspaceId}/design/apis")
    public DesignApiSpecResponse createApiSpec(
            @PathVariable String workspaceId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DesignApiSpecRequest request
    ) {
        return designService.createApiSpec(workspaceId, userId, request);
    }

    @PatchMapping("/design/apis/{apiSpecId}")
    public DesignApiSpecResponse updateApiSpec(
            @PathVariable String apiSpecId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DesignApiSpecRequest request
    ) {
        return designService.updateApiSpec(apiSpecId, userId, request);
    }

    @DeleteMapping("/design/apis/{apiSpecId}")
    public void deleteApiSpec(
            @PathVariable String apiSpecId,
            @AuthenticationPrincipal Long userId
    ) {
        designService.deleteApiSpec(apiSpecId, userId);
    }
}