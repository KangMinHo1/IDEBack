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

    /*
     * 요구사항·API 명세의 id 는 워크스페이스 안에서만 유일하다(프로젝트 복제본은 원본과
     * 같은 id 를 갖는다). 그래서 워크스페이스 경로를 정식으로 두고, 예전 경로는
     * workspaceId 쿼리를 선택으로 받아 그대로 살려 둔다. 예전 경로에 workspaceId 가 없고
     * 같은 id 가 내 프로젝트 둘 이상에 있으면 409 가 난다(DesignService 참고).
     */
    @PatchMapping({
            "/workspaces/{workspaceId}/design/requirements/{requirementId}",
            "/design/requirements/{requirementId}"
    })
    public DesignRequirementResponse updateRequirement(
            @PathVariable(required = false) String workspaceId,
            @RequestParam(name = "workspaceId", required = false) String workspaceIdParam,
            @PathVariable String requirementId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DesignRequirementRequest request
    ) {
        return designService.updateRequirement(
                firstNonBlank(workspaceId, workspaceIdParam), requirementId, userId, request);
    }

    @DeleteMapping({
            "/workspaces/{workspaceId}/design/requirements/{requirementId}",
            "/design/requirements/{requirementId}"
    })
    public void deleteRequirement(
            @PathVariable(required = false) String workspaceId,
            @RequestParam(name = "workspaceId", required = false) String workspaceIdParam,
            @PathVariable String requirementId,
            @AuthenticationPrincipal Long userId
    ) {
        designService.deleteRequirement(
                firstNonBlank(workspaceId, workspaceIdParam), requirementId, userId);
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

    @PatchMapping({
            "/workspaces/{workspaceId}/design/apis/{apiSpecId}",
            "/design/apis/{apiSpecId}"
    })
    public DesignApiSpecResponse updateApiSpec(
            @PathVariable(required = false) String workspaceId,
            @RequestParam(name = "workspaceId", required = false) String workspaceIdParam,
            @PathVariable String apiSpecId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DesignApiSpecRequest request
    ) {
        return designService.updateApiSpec(
                firstNonBlank(workspaceId, workspaceIdParam), apiSpecId, userId, request);
    }

    @DeleteMapping({
            "/workspaces/{workspaceId}/design/apis/{apiSpecId}",
            "/design/apis/{apiSpecId}"
    })
    public void deleteApiSpec(
            @PathVariable(required = false) String workspaceId,
            @RequestParam(name = "workspaceId", required = false) String workspaceIdParam,
            @PathVariable String apiSpecId,
            @AuthenticationPrincipal Long userId
    ) {
        designService.deleteApiSpec(
                firstNonBlank(workspaceId, workspaceIdParam), apiSpecId, userId);
    }

    private static String firstNonBlank(String pathValue, String queryValue) {
        if (pathValue != null && !pathValue.isBlank()) {
            return pathValue;
        }

        return queryValue == null || queryValue.isBlank() ? null : queryValue;
    }
}