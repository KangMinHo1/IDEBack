package com.myide.backend.controller.apitest;

import com.myide.backend.dto.apitest.DiscoveredEndpointResponse;
import com.myide.backend.service.apitest.ApiEndpointDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/api-tester")
@RequiredArgsConstructor
public class ApiEndpointDiscoveryController {

    private final ApiEndpointDiscoveryService service;

    @GetMapping("/workspaces/{workspaceId}/endpoints")
    public List<DiscoveredEndpointResponse> endpoints(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "master") String branchName
    ) {
        return service.findWorkspaceEndpoints(workspaceId, branchName);
    }
}
