package com.myide.backend.controller;

import com.myide.backend.domain.User;
import com.myide.backend.dto.ide.FileRequest;

import com.myide.backend.repository.UserRepository;
import com.myide.backend.service.GitService;
import com.myide.backend.service.ProjectService;
import com.myide.backend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final UserRepository userRepository;

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

    // 💡 [수정] 프론트엔드의 가짜 정보 대신 DB의 진짜 유저 정보를 사용합니다.
    @PostMapping("/commit")
    public ResponseEntity<String> commitChanges(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long currentUserId) { // 🚀 현재 로그인 유저 주입!

        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        // 🚀 DB에서 빼낸 진짜 이름(Nickname)과 진짜 이메일(Email)을 강제로 넣습니다.
        gitService.commit(repoPath, body.get("commitMessage"), user.getNickname(), user.getEmail());
        return ResponseEntity.ok("Commit successfully");
    }

    @PostMapping("/push")
    public ResponseEntity<String> pushToRemote(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long currentUserId) {

        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        String token = body.get("token");
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        if (token == null || token.trim().isEmpty()) {
            token = user.getGithubAccessToken();
            if (token == null || token.trim().isEmpty()) {
                return ResponseEntity.status(403).body("GITHUB_TOKEN_REQUIRED");
            }
        }

        try {
            gitService.push(repoPath, token);
            return ResponseEntity.ok("Push successfully");
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (errorMsg.contains("not authorized") || errorMsg.contains("authentication") ||
                    errorMsg.contains("permitted") || errorMsg.contains("could not read username")) {

                user.updateGithubAccessToken(null);
                userRepository.save(user);

                return ResponseEntity.status(403).body("GITHUB_TOKEN_EXPIRED");
            }
            return ResponseEntity.badRequest().body("푸시 실패: " + e.getMessage());
        }
    }

    // 💡 [수정] Pull 할 때도 DB의 진짜 유저 정보를 사용하도록 파라미터를 넘깁니다.
    @PostMapping("/pull")
    public ResponseEntity<String> pullFromRemote(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long currentUserId) {

        Path repoPath = workspaceService.getProjectPath(body.get("workspaceId"), body.get("projectName"), body.get("branchName"));
        String token = body.get("token");

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        if (token == null || token.trim().isEmpty()) {
            token = user.getGithubAccessToken();
            if (token == null || token.trim().isEmpty()) {
                return ResponseEntity.status(403).body("GITHUB_TOKEN_REQUIRED");
            }
        }

        try {
            // 🚀 pull 메서드에 유저의 진짜 닉네임과 이메일을 전달합니다.
            gitService.pull(repoPath, token, user.getNickname(), user.getEmail());
            return ResponseEntity.ok("Pull successfully");
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (errorMsg.contains("not authorized") || errorMsg.contains("authentication") ||
                    errorMsg.contains("permitted") || errorMsg.contains("could not read username")) {

                user.updateGithubAccessToken(null);
                userRepository.save(user);
                return ResponseEntity.status(403).body("GITHUB_TOKEN_EXPIRED");
            }
            return ResponseEntity.badRequest().body("Pull 실패: " + e.getMessage());
        }
    }

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

    @PostMapping("/sandbox/create")
    public ResponseEntity<String> createSandbox(@RequestBody Map<String, String> body) {
        String workspaceId = body.get("workspaceId");
        String projectName = body.get("projectName");
        String nickname = body.get("nickname");
        String taskName = body.get("taskName");

        String sandboxBranchName = "focus-" + nickname + "-" + taskName.replaceAll("\\s+", "-");

        FileRequest request = new FileRequest();
        request.setWorkspaceId(workspaceId);
        request.setProjectName(projectName);
        request.setBranchName(sandboxBranchName);

        projectService.createBranch(request);

        return ResponseEntity.ok(sandboxBranchName);
    }

    @PostMapping("/sandbox/apply")
    public ResponseEntity<String> applySandbox(@RequestBody Map<String, String> body) {
        String workspaceId = body.get("workspaceId");
        String projectName = body.get("projectName");
        String sandboxBranch = body.get("sandboxBranch");
        String commitMessage = body.get("commitMessage");
        String nickname = body.get("nickname");

        try {
            Path masterRepoPath = workspaceService.getProjectPath(workspaceId, projectName, "master");
            Path sandboxRepoPath = workspaceService.getProjectPath(workspaceId, projectName, sandboxBranch);

            try {
                gitService.stage(sandboxRepoPath, ".");
                gitService.commit(sandboxRepoPath, commitMessage, nickname, nickname + "@myide.com");
            } catch (Exception e) {
                System.out.println("커밋 건너뜀 (변경사항 없음): " + e.getMessage());
            }

            boolean isMergeSuccess = gitService.merge(masterRepoPath, sandboxBranch);

            if (!isMergeSuccess) {
                return ResponseEntity.status(409).body("충돌이 발생했습니다. 샌드박스가 보존되었습니다. 코드를 확인해주세요.");
            }

            gitService.deleteBranch(masterRepoPath, sandboxRepoPath, sandboxBranch);

            return ResponseEntity.ok("성공적으로 메인에 코드가 반영되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("병합 중 시스템 오류가 발생했습니다: " + e.getMessage());
        }
    }
}