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

    // 1. AI를 이용해 가상 트리 생성 (프리뷰용)
    @PostMapping("/generate")
    public ResponseEntity<RearrangeResponse> generateVirtualTree(
            @PathVariable String workspaceId,
            @RequestBody RearrangeRequest request) {
        return ResponseEntity.ok(rearrangeService.generateVirtualTree(request));
    }

    // 2. 특정 가상 트리를 에디터에 적용 (Active)
    @PostMapping("/{treeId}/activate")
    public ResponseEntity<Void> activateTree(
            @PathVariable String workspaceId,
            @PathVariable Long treeId) {
        rearrangeService.activateTree(workspaceId, treeId);
        return ResponseEntity.ok().build();
    }

    // 3. 원본 구조로 복구
    @PostMapping("/deactivate")
    public ResponseEntity<Void> deactivateTree(@PathVariable String workspaceId) {
        rearrangeService.deactivateAll(workspaceId);
        return ResponseEntity.ok().build();
    }

    // 4. 현재 적용 중인 가상 트리 조회 (에디터 로딩 시 호출)
    @GetMapping("/active")
    public ResponseEntity<RearrangeResponse> getActiveTree(@PathVariable String workspaceId) {
        RearrangeResponse activeTree = rearrangeService.getActiveTree(workspaceId);
        return activeTree != null ? ResponseEntity.ok(activeTree) : ResponseEntity.noContent().build();
    }

    // 💡 [NEW] 저장된 모든 가상 트리 목록 조회 (사이드바 드롭다운용)
    @GetMapping
    public ResponseEntity<List<RearrangeResponse>> getAllTrees(@PathVariable String workspaceId) {
        return ResponseEntity.ok(rearrangeService.getAllTrees(workspaceId));
    }
}