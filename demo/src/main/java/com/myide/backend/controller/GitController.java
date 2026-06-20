package com.myide.backend.controller;

import com.myide.backend.domain.User;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.service.GitService;
import com.myide.backend.service.NotificationService;
import com.myide.backend.service.ProjectService;
import com.myide.backend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.myide.backend.domain.notification.NotificationType;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/git")
@RequiredArgsConstructor
public class GitController {

    private static final Set<String> PROTECTED_BRANCHES = Set.of("master", "main");
    private static final Pattern SAFE_BRANCH_PATTERN = Pattern.compile("^[A-Za-z0-9._/-]+$");

    private final ProjectService projectService;
    private final WorkspaceService workspaceService;
    private final GitService gitService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    private String requireBodyValue(Map<String, String> body, String key, String message) {
        String value = body.get(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }

        return value.trim();
    }

    private boolean isProtectedBranch(String branchName) {
        return branchName != null
                && PROTECTED_BRANCHES.contains(branchName.toLowerCase(Locale.ROOT));
    }

    private String validateBranchName(String branchName) {
        if (branchName == null || branchName.trim().isEmpty()) {
            throw new IllegalArgumentException("브랜치명이 비어 있습니다.");
        }

        String normalized = branchName.trim();

        if (normalized.length() > 120) {
            throw new IllegalArgumentException("브랜치명이 너무 깁니다.");
        }

        if (!SAFE_BRANCH_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("브랜치명에는 영문, 숫자, '.', '_', '-', '/'만 사용할 수 있습니다.");
        }

        if (normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.startsWith("-")
                || normalized.contains("//")
                || normalized.contains("..")
                || normalized.contains("@{")
                || normalized.endsWith(".")
                || normalized.endsWith(".lock")) {
            throw new IllegalArgumentException("Git 브랜치명으로 사용할 수 없는 형식입니다.");
        }

        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (segment.isBlank()
                    || ".".equals(segment)
                    || segment.startsWith(".")
                    || segment.startsWith("-")
                    || segment.endsWith(".")) {
                throw new IllegalArgumentException("브랜치 경로의 각 구간은 비어 있거나 '.', '-' 로 시작/종료할 수 없습니다.");
            }
        }

        return normalized;
    }

    private String sanitizeBranchSegment(String value) {
        String sanitized = value == null ? "" : value.trim();

        sanitized = sanitized
                .replaceAll("[^A-Za-z0-9._-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+|-+$", "");

        if (sanitized.isBlank()) {
            return "dev";
        }

        return sanitized;
    }

    @GetMapping("/{workspaceId}/{projectName}/branches")
    public ResponseEntity<List<String>> getBranches(
            @PathVariable String workspaceId,
            @PathVariable String projectName) {

        return ResponseEntity.ok(projectService.getBranchList(workspaceId, projectName));
    }

    @PostMapping("/project/git-url")
    public ResponseEntity<String> updateGitUrl(@RequestBody Map<String, String> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String gitUrl = requireBodyValue(body, "gitUrl", "gitUrl이 없습니다.");

        projectService.updateProjectGitUrl(workspaceId, projectName, gitUrl);

        return ResponseEntity.ok("Git URL 업데이트 완료");
    }

    @PostMapping("/branches")
    public ResponseEntity<String> createBranch(@RequestBody FileRequest request) {
        if (request.getWorkspaceId() == null || request.getWorkspaceId().trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceId가 없습니다.");
        }

        if (request.getProjectName() == null || request.getProjectName().trim().isEmpty()) {
            throw new IllegalArgumentException("projectName이 없습니다.");
        }

        String branchName = validateBranchName(request.getBranchName());

        if (isProtectedBranch(branchName)) {
            return ResponseEntity.badRequest().body("master/main은 새 브랜치명으로 사용할 수 없습니다.");
        }

        request.setWorkspaceId(request.getWorkspaceId().trim());
        request.setProjectName(request.getProjectName().trim());
        request.setBranchName(branchName);

        projectService.createBranch(request);

        return ResponseEntity.ok("브랜치 생성됨");
    }

    /*
     * 브랜치 삭제 API 최종 방식
     *
     * DELETE /api/git/branches
     *
     * body:
     * {
     *   "workspaceId": "...",
     *   "projectName": "...",
     *   "branchName": "feature/login"
     * }
     *
     * branchName에 "/"가 들어가도 안전하게 처리하기 위해 URL path 방식은 제거합니다.
     */
    @DeleteMapping("/branches")
    public ResponseEntity<String> deleteBranchByBody(@RequestBody Map<String, String> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));

        if (isProtectedBranch(branchName)) {
            return ResponseEntity.badRequest().body("기본 브랜치는 삭제할 수 없습니다.");
        }

        Path masterRepoPath = workspaceService.getProjectPath(workspaceId, projectName, "master");
        Path worktreePath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.deleteBranch(masterRepoPath, worktreePath, branchName);

        return ResponseEntity.ok("브랜치가 안전하게 삭제되었습니다.");
    }

    @GetMapping("/{workspaceId}/{projectName}/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @PathVariable String workspaceId,
            @PathVariable String projectName,
            @RequestParam(defaultValue = "master") String branchName) {

        String normalizedBranchName = validateBranchName(branchName);
        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, normalizedBranchName);

        return ResponseEntity.ok(gitService.getStatus(repoPath));
    }

    @PostMapping("/stage")
    public ResponseEntity<String> stageFiles(@RequestBody Map<String, String> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));
        String filePattern = requireBodyValue(body, "filePattern", "filePattern이 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.stage(repoPath, filePattern);

        return ResponseEntity.ok("Staged successfully");
    }

    @PostMapping("/unstage")
    public ResponseEntity<String> unstageFiles(@RequestBody Map<String, String> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));
        String filePattern = requireBodyValue(body, "filePattern", "filePattern이 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.unstage(repoPath, filePattern);

        return ResponseEntity.ok("Unstaged successfully");
    }

    @PostMapping("/commit")
    public ResponseEntity<String> commitChanges(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long currentUserId) {

        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));
        String commitMessage = requireBodyValue(body, "commitMessage", "커밋 메시지가 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        gitService.commit(repoPath, commitMessage, user.getNickname(), user.getEmail());

        notificationService.notifyWorkspaceMembersExcept(
                workspaceId,
                currentUserId,
                NotificationType.GIT_COMMIT,
                "커밋 알림",
                user.getNickname() + "님이 " + branchName + " 브랜치에 커밋했습니다: " + commitMessage,
                "/projects/" + workspaceId + "?mode=team"
        );

        return ResponseEntity.ok("Commit successfully");
    }

    @PostMapping("/push")
    public ResponseEntity<String> pushToRemote(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long currentUserId) {

        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

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

            notificationService.notifyWorkspaceMembersExcept(
                    workspaceId,
                    currentUserId,
                    NotificationType.GIT_PUSH,
                    "푸쉬 알림",
                    user.getNickname() + "님이 " + branchName + " 브랜치로 푸쉬했습니다.",
                    "/projects/" + workspaceId + "?mode=team"
            );

            return ResponseEntity.ok("Push successfully");
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

            if (errorMsg.contains("not authorized")
                    || errorMsg.contains("authentication")
                    || errorMsg.contains("permitted")
                    || errorMsg.contains("could not read username")) {

                user.clearGithubInfo();
                userRepository.save(user);

                return ResponseEntity.status(403).body("GITHUB_TOKEN_EXPIRED");
            }

            return ResponseEntity.badRequest().body("푸시 실패: " + e.getMessage());
        }
    }

    @PostMapping("/pull")
    public ResponseEntity<String> pullFromRemote(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long currentUserId) {

        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

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
            gitService.pull(repoPath, token, user.getNickname(), user.getEmail());
            return ResponseEntity.ok("Pull successfully");
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

            if (errorMsg.contains("not authorized")
                    || errorMsg.contains("authentication")
                    || errorMsg.contains("permitted")
                    || errorMsg.contains("could not read username")) {

                user.clearGithubInfo();
                userRepository.save(user);

                return ResponseEntity.status(403).body("GITHUB_TOKEN_EXPIRED");
            }

            return ResponseEntity.badRequest().body("Pull 실패: " + e.getMessage());
        }
    }

    @PostMapping("/merge")
    public ResponseEntity<String> mergeBranch(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long currentUserId
    ) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));
        String targetBranch = validateBranchName(body.get("targetBranch"));

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        boolean isSuccess = gitService.merge(repoPath, targetBranch);

        if (!isSuccess) {
            notificationService.notifyWorkspaceMembersExcept(
                    workspaceId,
                    currentUserId,
                    NotificationType.ERROR,
                    "Git 충돌 알림",
                    branchName + " 브랜치 병합 중 충돌이 발생했습니다.",
                    "/projects/" + workspaceId + "?mode=team"
            );

            return ResponseEntity.ok("Merge conflict");
        }

        return ResponseEntity.ok("Merge successfully");
    }

    @PostMapping("/merge/abort")
    public ResponseEntity<String> abortMerge(@RequestBody Map<String, String> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.abortMerge(repoPath);

        return ResponseEntity.ok("Merge aborted successfully");
    }

    @GetMapping("/{workspaceId}/{projectName}/history")
    public ResponseEntity<List<Map<String, String>>> getHistory(
            @PathVariable String workspaceId,
            @PathVariable String projectName,
            @RequestParam(defaultValue = "master") String branchName) {

        String normalizedBranchName = validateBranchName(branchName);
        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, normalizedBranchName);

        return ResponseEntity.ok(gitService.getHistory(repoPath));
    }

    @PostMapping("/reset")
    public ResponseEntity<String> resetCommit(@RequestBody Map<String, String> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));
        String targetHash = requireBodyValue(body, "targetHash", "targetHash가 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.reset(repoPath, targetHash);

        return ResponseEntity.ok("Reset successfully");
    }

    @PostMapping("/checkout-commit")
    public ResponseEntity<String> checkoutCommit(@RequestBody Map<String, String> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(body.get("branchName"));
        String targetHash = requireBodyValue(body, "targetHash", "targetHash가 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.checkoutCommit(repoPath, targetHash);

        return ResponseEntity.ok("Checkout successfully");
    }

    @PostMapping("/sandbox/create")
    public ResponseEntity<String> createSandbox(@RequestBody Map<String, String> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String nickname = sanitizeBranchSegment(body.get("nickname"));
        String taskName = sanitizeBranchSegment(body.get("taskName"));

        String sandboxBranchName = validateBranchName("focus-" + nickname + "-" + taskName);

        FileRequest request = new FileRequest();
        request.setWorkspaceId(workspaceId);
        request.setProjectName(projectName);
        request.setBranchName(sandboxBranchName);

        projectService.createBranch(request);

        return ResponseEntity.ok(sandboxBranchName);
    }

    @PostMapping("/sandbox/apply")
    public ResponseEntity<String> applySandbox(@RequestBody Map<String, String> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String sandboxBranch = validateBranchName(body.get("sandboxBranch"));
        String commitMessage = requireBodyValue(body, "commitMessage", "커밋 메시지가 없습니다.");
        String nickname = sanitizeBranchSegment(body.get("nickname"));

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