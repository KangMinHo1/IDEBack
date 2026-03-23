// 경로: src/main/java/com/myide/backend/controller/RearrangeController.java
package com.myide.backend.controller;

import com.myide.backend.dto.rearrange.RearrangeRequest;
import com.myide.backend.dto.rearrange.RearrangeResponse;
import com.myide.backend.service.RearrangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/rearrange")
@RequiredArgsConstructor
public class RearrangeController {

    private final RearrangeService rearrangeService;

    @PostMapping("/generate")
    public ResponseEntity<RearrangeResponse> generateVirtualTree(
            @PathVariable String workspaceId,
            @RequestBody RearrangeRequest request) {

        request.setWorkspaceId(workspaceId);

        // 브랜치명 방어 (없으면 master)
        if (request.getBranchName() == null || request.getBranchName().trim().isEmpty()) {
            request.setBranchName("master");
        }

        if (request.getViewName() == null || request.getViewName().trim().isEmpty()) {
            String defaultName = request.getPrompt();
            if (defaultName != null && defaultName.length() > 15) {
                defaultName = defaultName.substring(0, 15) + "...";
            }
            request.setViewName(defaultName);
        }

        return ResponseEntity.ok(rearrangeService.generateVirtualTree(request));
    }

    @PostMapping("/{treeId}/activate")
    public ResponseEntity<Void> activateTree(
            @PathVariable String workspaceId,
            @PathVariable Long treeId,
            @RequestParam(defaultValue = "master") String branchName) { // 💡 [NEW] 쿼리 파라미터로 브랜치명 받음
        rearrangeService.activateTree(workspaceId, branchName, treeId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Void> deactivateTree(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "master") String branchName) { // 💡 [NEW]
        rearrangeService.deactivateAll(workspaceId, branchName);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/active")
    public ResponseEntity<RearrangeResponse> getActiveTree(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "master") String branchName) { // 💡 [NEW]
        RearrangeResponse activeTree = rearrangeService.getActiveTree(workspaceId, branchName);
        return activeTree != null ? ResponseEntity.ok(activeTree) : ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<RearrangeResponse>> getAllTrees(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "master") String branchName) { // 💡 [NEW]
        return ResponseEntity.ok(rearrangeService.getAllTrees(workspaceId, branchName));
    }

    @DeleteMapping("/{treeId}")
    public ResponseEntity<Void> deleteTree(
            @PathVariable String workspaceId,
            @PathVariable Long treeId) {
        rearrangeService.deleteTree(workspaceId, treeId);
        return ResponseEntity.ok().build();
    }

    // 💡 [NEW] 드래그 앤 드롭으로 수정된 트리를 저장하는 PUT API!
    @PutMapping("/{treeId}")
    public ResponseEntity<Void> updateVirtualTree(
            @PathVariable String workspaceId,
            @PathVariable Long treeId,
            @RequestBody com.myide.backend.dto.rearrange.UpdateVirtualTreeRequest request) {

        rearrangeService.updateVirtualTree(workspaceId, treeId, request.getTreeDataJson());
        return ResponseEntity.ok().build();
    }
}