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

    // 💡 [수정] 일반 병합 API에서 boolean 반환값 처리
    @PostMapping("/merge")
    public ResponseEntity<String> mergeBranch(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        boolean isSuccess = gitService.merge(repoPath, body.get("targetBranch"));

        if (!isSuccess) {
            return ResponseEntity.ok("Merge conflict");
        }
        return ResponseEntity.ok("Merge successfully");
    }

    @PostMapping("/merge/abort")
    public ResponseEntity<String> abortMerge(@RequestBody Map<String, String> body) {
        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        gitService.abortMerge(repoPath);
        return ResponseEntity.ok("Merge aborted successfully");
    }

    @GetMapping("/{workspaceId}/{projectName}/history")
    public ResponseEntity<List<Map<String, String>>> getHistory(@PathVariable String workspaceId, @PathVariable String projectName, @RequestParam(defaultValue = "master") String branchName) {

        // 💡 [핵심 수정] 히스토리 조회는 이미 지워졌을 수도 있는 branchName 폴더가 아니라,
        // 절대 지워지지 않는 안전한 "master" 폴더를 기준으로 실행하도록 강제 고정합니다!
        // (어차피 내부적으로 'git log --all'을 사용하므로 모든 브랜치 기록이 다 나옵니다.)
        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, "master");

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

    @DeleteMapping("/{workspaceId}/{projectName}/branches/{branchName}")
    public ResponseEntity<String> deleteBranch(
            @PathVariable String workspaceId,
            @PathVariable String projectName,
            @PathVariable String branchName) {

        if ("master".equalsIgnoreCase(branchName)) {
            return ResponseEntity.badRequest().body("master 브랜치는 삭제할 수 없습니다.");
        }

        Path masterRepoPath = workspaceService.getProjectPath(workspaceId, projectName, "master");
        Path worktreePath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.deleteBranch(masterRepoPath, worktreePath, branchName);
        return ResponseEntity.ok("브랜치가 안전하게 삭제되었습니다.");
    }

    // =========================================================================
    // 💡 포커스 샌드박스 (개인 집중 모드) 흐름을 위한 특화 API
    // =========================================================================

    @PostMapping("/sandbox/create")
    public ResponseEntity<String> createSandbox(@RequestBody Map<String, String> body) {
        String workspaceId = body.get("workspaceId");
        String projectName = body.get("projectName");
        String nickname = body.get("nickname");
        String taskName = body.get("taskName");

        // 🚨 [여기를 꼭 수정해주세요!] "focus/" ➡️ "focus-" 로 변경!!!
        String sandboxBranchName = "focus-" + nickname + "-" + taskName.replaceAll("\\s+", "-");

        FileRequest request = new FileRequest();
        request.setWorkspaceId(workspaceId);
        request.setProjectName(projectName);
        request.setBranchName(sandboxBranchName);

        projectService.createBranch(request);

        return ResponseEntity.ok(sandboxBranchName);
    }

    // 💡 [수정] 커밋 메시지를 받아와서 합치기 전에 정식 커밋을 남깁니다!
    @PostMapping("/sandbox/apply")
    public ResponseEntity<String> applySandbox(@RequestBody Map<String, String> body) {
        String workspaceId = body.get("workspaceId");
        String projectName = body.get("projectName");
        String sandboxBranch = body.get("sandboxBranch");
        String commitMessage = body.get("commitMessage"); // 프론트에서 받은 메시지
        String nickname = body.get("nickname"); // 커밋 작성자 이름

        try {
            Path masterRepoPath = workspaceService.getProjectPath(workspaceId, projectName, "master");
            Path sandboxRepoPath = workspaceService.getProjectPath(workspaceId, projectName, sandboxBranch);

            // 1. 🚨 [Git 원칙 준수] 병합하기 직전, 샌드박스의 모든 변경사항을 유저가 작성한 메시지로 커밋!
            try {
                gitService.stage(sandboxRepoPath, ".");
                // 작성자 이메일은 가짜로 넣어도 무방합니다. (예: nickname@myide.com)
                gitService.commit(sandboxRepoPath, commitMessage, nickname, nickname + "@myide.com");
            } catch (Exception e) {
                // 수정한 게 없어서 커밋할 게 없는 경우는 무시하고 다음 단계(병합)로 넘어갑니다.
                System.out.println("커밋 건너뜀 (변경사항 없음): " + e.getMessage());
            }

            // 2. 메인 브랜치로 병합 시도
            boolean isMergeSuccess = gitService.merge(masterRepoPath, sandboxBranch);

            // 3. 충돌 발생 시 방어
            if (!isMergeSuccess) {
                return ResponseEntity.status(409).body("충돌이 발생했습니다. 샌드박스가 보존되었습니다. 코드를 확인해주세요.");
            }

            // 4. 성공 시 샌드박스 철거
            gitService.deleteBranch(masterRepoPath, sandboxRepoPath, sandboxBranch);

            return ResponseEntity.ok("성공적으로 메인에 코드가 반영되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("병합 중 시스템 오류가 발생했습니다: " + e.getMessage());
        }
    }
}