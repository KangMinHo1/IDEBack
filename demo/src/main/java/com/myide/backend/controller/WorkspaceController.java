package com.myide.backend.controller;

import com.myide.backend.domain.Workspace;
import com.myide.backend.service.FileSystemService;
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

    private final FileSystemService fileSystemService;

    // 내 워크스페이스 목록 조회
    @GetMapping
    public ResponseEntity<List<Workspace>> getMyWorkspaces(@RequestParam String userId) {
        return ResponseEntity.ok(fileSystemService.getMyWorkspaces(userId));
    }

    // 워크스페이스 생성
    @PostMapping
    public ResponseEntity<Workspace> createWorkspace(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String name = body.get("name");
        return ResponseEntity.ok(fileSystemService.createWorkspace(userId, name));
    }
}