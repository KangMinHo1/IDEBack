package com.myide.backend.controller;

import com.myide.backend.dto.FileRequest;
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
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String workspaceId, @PathVariable String projectName, @RequestParam(defaultValue = "main-repo") String branchName) {
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

    // 7. Commit (커밋하기)
    @PostMapping("/commit")
    public ResponseEntity<String> commitChanges(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));

        // 프론트에서 넘어온 작성자 정보 추출
        String authorName = body.get("authorName");
        String authorEmail = body.get("authorEmail");

        // GitService로 전달
        gitService.commit(repoPath, body.get("commitMessage"), authorName, authorEmail);
        return ResponseEntity.ok("Commit successfully");
    }

    // 8. Push (원격 저장소로 밀어올리기)
    @PostMapping("/push")
    public ResponseEntity<String> pushToRemote(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));

        // 프론트에서 넘어온 깃허브 토큰
        String token = body.get("token");

        gitService.push(repoPath, token);
        return ResponseEntity.ok("Push successfully");
    }

    // 9. Pull
    @PostMapping("/pull")
    public ResponseEntity<String> pullFromRemote(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.pull(repoPath, body.get("token"));
        return ResponseEntity.ok("Pull successfully");
    }

    // 10. Merge
    @PostMapping("/merge")
    public ResponseEntity<String> mergeBranch(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.merge(repoPath, body.get("targetBranch"));
        return ResponseEntity.ok("Merge successfully");
    }

    // 11. History (로그 목록)
    @GetMapping("/{workspaceId}/{projectName}/history")
    public ResponseEntity<List<Map<String, String>>> getHistory(@PathVariable String workspaceId, @PathVariable String projectName, @RequestParam(defaultValue = "main-repo") String branchName) {
        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
        return ResponseEntity.ok(gitService.getHistory(repoPath));
    }
}