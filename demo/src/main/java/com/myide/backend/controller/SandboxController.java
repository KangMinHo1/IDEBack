// 경로: src/main/java/com/myide/backend/controller/SandboxController.java
package com.myide.backend.controller;

import com.myide.backend.security.WorkspaceAccessGuard;
import com.myide.backend.service.saferun.BranchKey;
import com.myide.backend.service.sandbox.SandboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 샌드박스(개인 레이어) API.
 *
 * 예전 샌드박스(/api/git/sandbox/create, /apply)는 개인 브랜치를 만들었다. 이 API 는
 * 브랜치를 만들지도 바꾸지도 않는다. 같은 브랜치에 머문 채 내가 고친 파일만 따로 둔다.
 *
 * 누구의 샌드박스인지는 요청 본문이 아니라 토큰에서 읽는다. 그래서 남의 샌드박스를
 * 읽거나 반영할 길이 없다.
 *
 * 프론트가 지켜야 할 순서와 기존 API 와의 대응은 docs/safe-run-sandbox.md 에 있다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sandbox")
public class SandboxController {

    private final SandboxService sandboxService;
    private final WorkspaceAccessGuard accessGuard;

    /**
     * 모든 요청이 같은 모양의 본문을 쓴다. 쓰지 않는 필드는 비워 둔다.
     *
     * @param liveContent 브라우저가 팀 공유 문서에서 읽은 지금 내용(fork, merge, resolve)
     * @param code        저장할 내용(PUT /file, resolve)
     */
    public record SandboxRequest(
            String workspaceId,
            String projectName,
            String branchName,
            String filePath,
            String liveContent,
            String code
    ) {}

    public record FileContent(String filePath, String content) {}

    @PostMapping("/enable")
    public SandboxService.State enable(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        return sandboxService.enable(branchOf(request, userId), userId);
    }

    @PostMapping("/disable")
    public SandboxService.State disable(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        return sandboxService.disable(branchOf(request, userId), userId);
    }

    @GetMapping("/state")
    public SandboxService.State state(
            @AuthenticationPrincipal Long userId,
            @RequestParam String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "master") String branchName
    ) {
        return sandboxService.state(branchOf(workspaceId, projectName, branchName, userId), userId);
    }

    @PostMapping("/files/fork")
    public FileContent fork(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        String content = sandboxService.fork(
                branchOf(request, userId), userId, request.filePath(), request.liveContent());

        return new FileContent(request.filePath(), content);
    }

    @GetMapping("/file")
    public FileContent readFile(
            @AuthenticationPrincipal Long userId,
            @RequestParam String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "master") String branchName,
            @RequestParam String path
    ) {
        BranchKey key = branchOf(workspaceId, projectName, branchName, userId);

        return new FileContent(path, sandboxService.readFile(key, userId, path));
    }

    @PutMapping("/file")
    public ResponseEntity<Void> saveFile(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        sandboxService.saveFile(branchOf(request, userId), userId, request.filePath(), request.code());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/file")
    public ResponseEntity<Void> deleteFile(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        sandboxService.deleteFile(branchOf(request, userId), userId, request.filePath());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/merge")
    public SandboxService.MergeView merge(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        return sandboxService.merge(
                branchOf(request, userId), userId, request.filePath(), request.liveContent());
    }

    @PostMapping("/resolve")
    public ResponseEntity<Void> resolve(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        sandboxService.resolve(
                branchOf(request, userId), userId, request.filePath(), request.code(), request.liveContent());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/applied")
    public ResponseEntity<Void> applied(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        sandboxService.markApplied(branchOf(request, userId), userId, request.filePath());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apply-direct")
    public ResponseEntity<?> applyDirect(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        try {
            return ResponseEntity.ok(
                    sandboxService.applyDirect(branchOf(request, userId), userId, request.filePath()));
        } catch (SandboxService.RoomOpenException e) {
            // 오류가 아니라 "이 파일은 브라우저 쪽 반영을 써야 한다"는 안내다.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "ROOM_OPEN",
                    "filePath", String.valueOf(request.filePath()),
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/discard")
    public ResponseEntity<Void> discard(@AuthenticationPrincipal Long userId, @RequestBody SandboxRequest request) {
        sandboxService.discard(branchOf(request, userId), userId);
        return ResponseEntity.noContent().build();
    }

    private BranchKey branchOf(SandboxRequest request, Long userId) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다.");
        }

        return branchOf(request.workspaceId(), request.projectName(), request.branchName(), userId);
    }

    private BranchKey branchOf(String workspaceId, String projectName, String branchName, Long userId) {
        accessGuard.requireAccess(workspaceId, userId);

        if (projectName == null || projectName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectName이 없습니다.");
        }

        return BranchKey.of(workspaceId, projectName, branchName);
    }
}
