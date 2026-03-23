package com.myide.backend.controller;

import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.service.GitService;
import com.myide.backend.service.ProjectService;
import com.myide.backend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/git")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class GitController {

    private final ProjectService projectService;
    private final WorkspaceService workspaceService;
    private final GitService gitService;

    @GetMapping("/{workspaceId}/{projectName}/branches")
    public ResponseEntity<List<String>> getBranches(@PathVariable String workspaceId, @PathVariable String projectName) {
        return ResponseEntity.ok(projectService.getBranchList(workspaceId, projectName));
    }

    @PostMapping("/project/git-url")
    public ResponseEntity<String> updateGitUrl(@RequestBody Map<String, String> body) {
        projectService.updateProjectGitUrl(body.get("workspaceId"), body.get("projectName"), body.get("gitUrl"));
        return ResponseEntity.ok("Git URL 업데이트 완료");
    }

    @PostMapping("/branches")
    public ResponseEntity<String> createBranch(@RequestBody FileRequest request) {
        projectService.createBranch(request);
        return ResponseEntity.ok("브랜치 생성됨");
    }

    @GetMapping("/{workspaceId}/{projectName}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String workspaceId, @PathVariable String projectName, @RequestParam(defaultValue = "master") String branchName) {
        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
        return ResponseEntity.ok(gitService.getStatus(repoPath));
    }

    @PostMapping("/stage")
    public ResponseEntity<String> stageFiles(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.stage(repoPath, body.get("filePattern"));
        return ResponseEntity.ok("Staged successfully");
    }

    @PostMapping("/unstage")
    public ResponseEntity<String> unstageFiles(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.unstage(repoPath, body.get("filePattern"));
        return ResponseEntity.ok("Unstaged successfully");
    }

    @PostMapping("/commit")
    public ResponseEntity<String> commitChanges(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        String authorName = body.get("authorName");
        String authorEmail = body.get("authorEmail");
        gitService.commit(repoPath, body.get("commitMessage"), authorName, authorEmail);
        return ResponseEntity.ok("Commit successfully");
    }

    @PostMapping("/push")
    public ResponseEntity<String> pushToRemote(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        String token = body.get("token");
        gitService.push(repoPath, token);
        return ResponseEntity.ok("Push successfully");
    }

    @PostMapping("/pull")
    public ResponseEntity<String> pullFromRemote(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.pull(repoPath, body.get("token"));
        return ResponseEntity.ok("Pull successfully");
    }

    @PostMapping("/merge")
    public ResponseEntity<String> mergeBranch(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.merge(repoPath, body.get("targetBranch"));
        return ResponseEntity.ok("Merge successfully");
    }

    // 💡 [New] 병합 취소 (Abort) API 추가!
    @PostMapping("/merge/abort")
    public ResponseEntity<String> abortMerge(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.abortMerge(repoPath);
        return ResponseEntity.ok("Merge aborted successfully");
    }

    @GetMapping("/{workspaceId}/{projectName}/history")
    public ResponseEntity<List<Map<String, String>>> getHistory(@PathVariable String workspaceId, @PathVariable String projectName, @RequestParam(defaultValue = "master") String branchName) {
        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
        return ResponseEntity.ok(gitService.getHistory(repoPath));
    }

    @PostMapping("/reset")
    public ResponseEntity<String> resetCommit(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.reset(repoPath, body.get("targetHash"));
        return ResponseEntity.ok("Reset successfully");
    }

    @PostMapping("/checkout-commit")
    public ResponseEntity<String> checkoutCommit(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.checkoutCommit(repoPath, body.get("targetHash"));
        return ResponseEntity.ok("Checkout successfully");
    }
    
    //  브랜치 삭제 API
    // =========================================================================
    @DeleteMapping("/{workspaceId}/{projectName}/branches/{branchName}")
    public ResponseEntity<String> deleteBranch(
            @PathVariable String workspaceId,
            @PathVariable String projectName,
            @PathVariable String branchName) {

        // 마스터 브랜치는 절대 삭제 불가! (방어 로직)
        if ("master".equalsIgnoreCase(branchName)) {
            return ResponseEntity.badRequest().body("master 브랜치는 삭제할 수 없습니다.");
        }

        // 삭제 명령은 항상 기준점인 'master' 폴더에서 실행해야 안전함
        Path masterRepoPath = workspaceService.getProjectPath(workspaceId, projectName, "master");
        Path worktreePath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.deleteBranch(masterRepoPath, worktreePath, branchName);
        return ResponseEntity.ok("브랜치가 안전하게 삭제되었습니다.");
    }
}