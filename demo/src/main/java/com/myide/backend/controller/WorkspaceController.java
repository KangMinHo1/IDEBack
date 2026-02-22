package com.myide.backend.controller;

import com.myide.backend.domain.Workspace;
import com.myide.backend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WorkspaceController {

    // 💡 [수정] 서비스 주입 변경
    private final WorkspaceService workspaceService;

    // 내 워크스페이스 목록 조회
    @GetMapping
    public ResponseEntity<List<Workspace>> getMyWorkspaces(@RequestParam String userId) {
        return ResponseEntity.ok(workspaceService.getMyWorkspaces(userId));
    }

    // 워크스페이스 생성 (path 파라미터 수신)
    @PostMapping
    public ResponseEntity<Workspace> createWorkspace(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String name = body.get("name");
        String path = body.get("path"); // 경로 정보 (없으면 null)

        // 💡 [수정] workspaceService 호출
        return ResponseEntity.ok(workspaceService.createWorkspace(userId, name, path));
    }
}