package com.myide.backend.controller;

import com.myide.backend.domain.User;
import com.myide.backend.domain.notification.NotificationType;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.handler.WorkspaceEventWebSocketHandler;
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

    private static final String DEFAULT_BRANCH_NAME = "master";
    private static final Set<String> PROTECTED_BRANCHES = Set.of("master", "main");
    private static final Pattern SAFE_BRANCH_PATTERN = Pattern.compile("^[A-Za-z0-9._/-]+$");

    private final ProjectService projectService;
    private final WorkspaceService workspaceService;
    private final GitService gitService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final WorkspaceEventWebSocketHandler workspaceEventWebSocketHandler;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    private String requireBodyValue(Map<String, ?> body, String key, String message) {
        Object value = body.get(key);

        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }

        return String.valueOf(value).trim();
    }

    private String optionalBodyValue(Map<String, ?> body, String key) {
        Object value = body.get(key);

        if (value == null) {
            return "";
        }

        return String.valueOf(value).trim();
    }

    private boolean optionalBooleanValue(Map<String, ?> body, String key, boolean defaultValue) {
        Object value = body.get(key);

        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        String text = String.valueOf(value).trim();

        if (text.isEmpty()) {
            return defaultValue;
        }

        return "true".equalsIgnoreCase(text)
                || "yes".equalsIgnoreCase(text)
                || "1".equals(text);
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

    private String validateOptionalBranchName(String branchName, String defaultBranchName) {
        if (branchName == null || branchName.trim().isEmpty()) {
            return defaultBranchName;
        }

        return validateBranchName(branchName);
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

    private User getCurrentUser(Long currentUserId) {
        return userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
    }

    private String resolveGithubToken(User user, String requestToken) {
        String token = requestToken;

        if (token == null || token.trim().isEmpty()) {
            token = user.getGithubAccessToken();
        }

        if (token == null || token.trim().isEmpty()) {
            throw new IllegalStateException("GITHUB_TOKEN_REQUIRED");
        }

        return token;
    }

    private boolean isGithubAuthError(Exception e) {
        String errorMsg = e.getMessage() != null
                ? e.getMessage().toLowerCase(Locale.ROOT)
                : "";

        return errorMsg.contains("not authorized")
                || errorMsg.contains("authentication")
                || errorMsg.contains("permitted")
                || errorMsg.contains("could not read username")
                || errorMsg.contains("invalid username or password")
                || errorMsg.contains("repository not found");
    }

    private ResponseEntity<String> handleGithubCommandFailure(Exception e, User user, String fallbackPrefix) {
        if (e instanceof IllegalStateException
                && "GITHUB_TOKEN_REQUIRED".equals(e.getMessage())) {
            return ResponseEntity.status(403).body("GITHUB_TOKEN_REQUIRED");
        }

        if (isGithubAuthError(e)) {
            user.clearGithubInfo();
            userRepository.save(user);

            return ResponseEntity.status(403).body("GITHUB_TOKEN_EXPIRED");
        }

        return ResponseEntity.badRequest().body(fallbackPrefix + ": " + e.getMessage());
    }

    private void notifyIfPossible(
            String workspaceId,
            Long currentUserId,
            NotificationType type,
            String title,
            String message
    ) {
        if (currentUserId == null) {
            return;
        }

        notificationService.notifyWorkspaceMembersExcept(
                workspaceId,
                currentUserId,
                type,
                title,
                message,
                "/projects/" + workspaceId + "?mode=team"
        );
    }

    @GetMapping("/{workspaceId}/{projectName}/branches")
    public ResponseEntity<List<String>> getBranches(
            @PathVariable String workspaceId,
            @PathVariable String projectName
    ) {
        return ResponseEntity.ok(projectService.getBranchList(workspaceId, projectName));
    }

    @PostMapping("/project/git-url")
    public ResponseEntity<String> updateGitUrl(@RequestBody Map<String, ?> body) {
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
        String baseBranch = validateOptionalBranchName(request.getBaseBranch(), DEFAULT_BRANCH_NAME);

        if (isProtectedBranch(branchName)) {
            return ResponseEntity.badRequest().body("master/main은 새 브랜치명으로 사용할 수 없습니다.");
        }

        request.setWorkspaceId(request.getWorkspaceId().trim());
        request.setProjectName(request.getProjectName().trim());
        request.setBranchName(branchName);
        request.setBaseBranch(baseBranch);

        projectService.createBranch(request);

        return ResponseEntity.ok("브랜치 생성됨");
    }

    @DeleteMapping("/branches")
    public ResponseEntity<String> deleteBranchByBody(@RequestBody Map<String, ?> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));

        if (isProtectedBranch(branchName)) {
            return ResponseEntity.badRequest().body("기본 브랜치는 삭제할 수 없습니다.");
        }

        Path masterRepoPath = workspaceService.getProjectPath(workspaceId, projectName, DEFAULT_BRANCH_NAME);
        Path worktreePath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.deleteBranch(masterRepoPath, worktreePath, branchName);

        return ResponseEntity.ok("브랜치가 안전하게 삭제되었습니다.");
    }

    @GetMapping("/{workspaceId}/{projectName}/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @PathVariable String workspaceId,
            @PathVariable String projectName,
            @RequestParam(defaultValue = DEFAULT_BRANCH_NAME) String branchName
    ) {
        String normalizedBranchName = validateBranchName(branchName);
        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, normalizedBranchName);

        return ResponseEntity.ok(gitService.getStatus(repoPath));
    }

    @PostMapping("/stage")
    public ResponseEntity<String> stageFiles(@RequestBody Map<String, ?> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));
        String filePattern = requireBodyValue(body, "filePattern", "filePattern이 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.stage(repoPath, filePattern);

        return ResponseEntity.ok("Staged successfully");
    }

    @PostMapping("/unstage")
    public ResponseEntity<String> unstageFiles(@RequestBody Map<String, ?> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));
        String filePattern = requireBodyValue(body, "filePattern", "filePattern이 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.unstage(repoPath, filePattern);

        return ResponseEntity.ok("Unstaged successfully");
    }

    @PostMapping("/commit")
    public ResponseEntity<String> commitChanges(
            @RequestBody Map<String, ?> body,
            @AuthenticationPrincipal Long currentUserId
    ) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));
        String commitMessage = requireBodyValue(body, "commitMessage", "커밋 메시지가 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        User user = getCurrentUser(currentUserId);

        gitService.commit(repoPath, commitMessage, user.getNickname(), user.getEmail());

        notifyIfPossible(
                workspaceId,
                currentUserId,
                NotificationType.GIT_COMMIT,
                "커밋 알림",
                user.getNickname() + "님이 " + branchName + " 브랜치에 커밋했습니다: " + commitMessage
        );

        return ResponseEntity.ok("Commit successfully");
    }

    @PostMapping("/fetch")
    public ResponseEntity<String> fetchRemote(
            @RequestBody Map<String, ?> body,
            @AuthenticationPrincipal Long currentUserId
    ) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateOptionalBranchName(
                optionalBodyValue(body, "branchName"),
                DEFAULT_BRANCH_NAME
        );

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        User user = getCurrentUser(currentUserId);

        try {
            String token = resolveGithubToken(user, optionalBodyValue(body, "token"));
            gitService.fetch(repoPath, token);

            return ResponseEntity.ok("Fetch successfully");
        } catch (Exception e) {
            return handleGithubCommandFailure(e, user, "Fetch 실패");
        }
    }

    @PostMapping("/push")
    public ResponseEntity<String> pushToRemote(
            @RequestBody Map<String, ?> body,
            @AuthenticationPrincipal Long currentUserId
    ) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        User user = getCurrentUser(currentUserId);

        try {
            String token = resolveGithubToken(user, optionalBodyValue(body, "token"));
            gitService.push(repoPath, token);

            notifyIfPossible(
                    workspaceId,
                    currentUserId,
                    NotificationType.GIT_PUSH,
                    "푸쉬 알림",
                    user.getNickname() + "님이 " + branchName + " 브랜치로 푸쉬했습니다."
            );

            return ResponseEntity.ok("Push successfully");
        } catch (Exception e) {
            return handleGithubCommandFailure(e, user, "푸시 실패");
        }
    }

    @PostMapping("/pull")
    public ResponseEntity<String> pullFromRemote(
            @RequestBody Map<String, ?> body,
            @AuthenticationPrincipal Long currentUserId
    ) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        User user = getCurrentUser(currentUserId);

        try {
            String token = resolveGithubToken(user, optionalBodyValue(body, "token"));
            gitService.pull(repoPath, token, user.getNickname(), user.getEmail());

            return ResponseEntity.ok("Pull successfully");
        } catch (Exception e) {
            return handleGithubCommandFailure(e, user, "Pull 실패");
        }
    }

    /*
     * 기존 호환 API.
     *
     * 의미:
     * branchName 브랜치에 targetBranch를 병합합니다.
     */
    @PostMapping("/merge")
    public ResponseEntity<String> mergeBranch(
            @RequestBody Map<String, ?> body,
            @AuthenticationPrincipal Long currentUserId
    ) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));
        String targetBranch = validateBranchName(requireBodyValue(body, "targetBranch", "targetBranch가 없습니다."));

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        boolean isSuccess = gitService.merge(repoPath, targetBranch);

        if (!isSuccess) {
            notifyIfPossible(
                    workspaceId,
                    currentUserId,
                    NotificationType.ERROR,
                    "Git 충돌 알림",
                    branchName + " 브랜치 병합 중 충돌이 발생했습니다."
            );

            return ResponseEntity.ok("Merge conflict");
        }

        return ResponseEntity.ok("Merge successfully");
    }

    /*
     * Sourcetree식 브랜치 병합 API.
     *
     * sourceBranch -> targetBranch
     *
     * 예:
     * feature/login -> develop
     * develop -> master
     */
    @PostMapping("/branches/merge")
    public ResponseEntity<?> mergeBranches(
            @RequestBody Map<String, ?> body,
            @AuthenticationPrincipal Long currentUserId
    ) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String sourceBranch = validateBranchName(requireBodyValue(body, "sourceBranch", "sourceBranch가 없습니다."));
        String targetBranch = validateBranchName(requireBodyValue(body, "targetBranch", "targetBranch가 없습니다."));
        String mergeMode = optionalBodyValue(body, "mergeMode");

        boolean deleteSourceAfterMerge = optionalBooleanValue(
                body,
                "deleteSourceAfterMerge",
                false
        );

        if (sourceBranch.equals(targetBranch)) {
            return ResponseEntity.badRequest().body("같은 브랜치끼리는 병합할 수 없습니다.");
        }

        Path masterRepoPath = workspaceService.getProjectPath(
                workspaceId,
                projectName,
                DEFAULT_BRANCH_NAME
        );

        if (!gitService.branchExists(masterRepoPath, sourceBranch)) {
            return ResponseEntity.badRequest().body("병합할 브랜치를 찾을 수 없습니다: " + sourceBranch);
        }

        if (!gitService.branchExists(masterRepoPath, targetBranch)) {
            return ResponseEntity.badRequest().body("병합 받을 브랜치를 찾을 수 없습니다: " + targetBranch);
        }

        Path targetRepoPath = workspaceService.getProjectPath(
                workspaceId,
                projectName,
                targetBranch
        );

        boolean noFastForward = !"FF".equalsIgnoreCase(mergeMode)
                && !"FAST_FORWARD".equalsIgnoreCase(mergeMode);

        boolean isSuccess = gitService.mergeBranchInto(
                targetRepoPath,
                sourceBranch,
                noFastForward
        );

        if (!isSuccess) {
            notifyIfPossible(
                    workspaceId,
                    currentUserId,
                    NotificationType.ERROR,
                    "Git 충돌 알림",
                    sourceBranch + " → " + targetBranch + " 병합 중 충돌이 발생했습니다."
            );

            return ResponseEntity
                    .status(409)
                    .body(Map.of(
                            "message", "Merge conflict",
                            "sourceBranch", sourceBranch,
                            "targetBranch", targetBranch
                    ));
        }

        if (deleteSourceAfterMerge && !isProtectedBranch(sourceBranch)) {
            Path sourceWorktreePath = workspaceService.getProjectPath(
                    workspaceId,
                    projectName,
                    sourceBranch
            );

            gitService.deleteBranch(masterRepoPath, sourceWorktreePath, sourceBranch);
        }

        String targetRevision = gitService.getHeadHash(targetRepoPath);

        notifyIfPossible(
                workspaceId,
                currentUserId,
                NotificationType.GIT_COMMIT,
                "브랜치 병합 알림",
                sourceBranch + " 브랜치가 " + targetBranch + " 브랜치에 병합되었습니다."
        );

        return ResponseEntity.ok(
                Map.of(
                        "message", "Merge successfully",
                        "sourceBranch", sourceBranch,
                        "targetBranch", targetBranch,
                        "revision", targetRevision
                )
        );
    }

    /*
     * api.js의 mergeCommitApi 호환용.
     *
     * 기존 프론트가 /api/git/merge/start로 targetHash를 보내고 있으므로 유지합니다.
     * Git에서는 commit hash도 merge 대상이 될 수 있습니다.
     */
    @PostMapping("/merge/start")
    public ResponseEntity<?> mergeCommitStart(
            @RequestBody Map<String, ?> body,
            @AuthenticationPrincipal Long currentUserId
    ) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));
        String targetHash = requireBodyValue(body, "targetHash", "targetHash가 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        boolean isSuccess = gitService.mergeBranchInto(repoPath, targetHash, false);

        if (!isSuccess) {
            notifyIfPossible(
                    workspaceId,
                    currentUserId,
                    NotificationType.ERROR,
                    "Git 충돌 알림",
                    branchName + " 브랜치에서 커밋 병합 중 충돌이 발생했습니다."
            );

            return ResponseEntity
                    .status(409)
                    .body(Map.of(
                            "message", "Merge conflict",
                            "branchName", branchName,
                            "targetHash", targetHash
                    ));
        }

        return ResponseEntity.ok(
                Map.of(
                        "message", "Merge successfully",
                        "branchName", branchName,
                        "targetHash", targetHash,
                        "revision", gitService.getHeadHash(repoPath)
                )
        );
    }

    @PostMapping("/merge/abort")
    public ResponseEntity<String> abortMerge(@RequestBody Map<String, ?> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.abortMerge(repoPath);

        return ResponseEntity.ok("Merge aborted successfully");
    }

    @GetMapping("/{workspaceId}/{projectName}/history")
    public ResponseEntity<List<Map<String, String>>> getHistory(
            @PathVariable String workspaceId,
            @PathVariable String projectName,
            @RequestParam(defaultValue = DEFAULT_BRANCH_NAME) String branchName
    ) {
        String normalizedBranchName = validateBranchName(branchName);
        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, normalizedBranchName);

        return ResponseEntity.ok(gitService.getHistory(repoPath));
    }

    @PostMapping("/reset")
    public ResponseEntity<String> resetCommit(@RequestBody Map<String, ?> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));
        String targetHash = requireBodyValue(body, "targetHash", "targetHash가 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.reset(repoPath, targetHash);

        return ResponseEntity.ok("Reset successfully");
    }

    @PostMapping("/checkout-commit")
    public ResponseEntity<String> checkoutCommit(@RequestBody Map<String, ?> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String branchName = validateBranchName(requireBodyValue(body, "branchName", "branchName이 없습니다."));
        String targetHash = requireBodyValue(body, "targetHash", "targetHash가 없습니다.");

        Path repoPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        gitService.checkoutCommit(repoPath, targetHash);

        return ResponseEntity.ok("Checkout successfully");
    }

    @PostMapping("/sandbox/create")
    public ResponseEntity<String> createSandbox(@RequestBody Map<String, ?> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String nickname = sanitizeBranchSegment(optionalBodyValue(body, "nickname"));
        String taskName = sanitizeBranchSegment(optionalBodyValue(body, "taskName"));

        String requestedBaseBranch = validateOptionalBranchName(
                optionalBodyValue(body, "baseBranch"),
                DEFAULT_BRANCH_NAME
        );

        String sandboxBranchName = validateBranchName("focus-" + nickname + "-" + taskName);

        Path masterRepoPath = workspaceService.getProjectPath(
                workspaceId,
                projectName,
                DEFAULT_BRANCH_NAME
        );

        if (!gitService.branchExists(masterRepoPath, requestedBaseBranch)) {
            return ResponseEntity.badRequest()
                    .body("샌드박스 기준 브랜치를 찾을 수 없습니다: " + requestedBaseBranch);
        }

        if (requestedBaseBranch.startsWith("focus-") || requestedBaseBranch.startsWith("focus/")) {
            return ResponseEntity.badRequest()
                    .body("샌드박스 브랜치를 기준으로 새 샌드박스를 만들 수 없습니다.");
        }

        FileRequest request = new FileRequest();
        request.setWorkspaceId(workspaceId);
        request.setProjectName(projectName);
        request.setBranchName(sandboxBranchName);
        request.setBaseBranch(requestedBaseBranch);
        request.setCheckoutAfterCreate(true);

        projectService.createBranch(request);

        return ResponseEntity.ok(sandboxBranchName);
    }

    @PostMapping("/sandbox/apply")
    public ResponseEntity<?> applySandbox(@RequestBody Map<String, ?> body) {
        String workspaceId = requireBodyValue(body, "workspaceId", "workspaceId가 없습니다.");
        String projectName = requireBodyValue(body, "projectName", "projectName이 없습니다.");
        String sandboxBranch = validateBranchName(requireBodyValue(body, "sandboxBranch", "sandboxBranch가 없습니다."));
        String targetBranch = validateOptionalBranchName(
                optionalBodyValue(body, "targetBranch"),
                DEFAULT_BRANCH_NAME
        );
        String commitMessage = requireBodyValue(body, "commitMessage", "커밋 메시지가 없습니다.");
        String nickname = sanitizeBranchSegment(optionalBodyValue(body, "nickname"));

        if (sandboxBranch.equals(targetBranch)) {
            return ResponseEntity.badRequest().body("샌드박스 브랜치와 대상 브랜치가 같습니다.");
        }

        try {
            Path masterRepoPath = workspaceService.getProjectPath(
                    workspaceId,
                    projectName,
                    DEFAULT_BRANCH_NAME
            );

            Path targetRepoPath = workspaceService.getProjectPath(
                    workspaceId,
                    projectName,
                    targetBranch
            );

            Path sandboxRepoPath = workspaceService.getProjectPath(
                    workspaceId,
                    projectName,
                    sandboxBranch
            );

            if (!gitService.branchExists(masterRepoPath, sandboxBranch)) {
                return ResponseEntity.badRequest().body("샌드박스 브랜치를 찾을 수 없습니다: " + sandboxBranch);
            }

            if (!gitService.branchExists(masterRepoPath, targetBranch)) {
                return ResponseEntity.badRequest().body("병합 대상 브랜치를 찾을 수 없습니다: " + targetBranch);
            }

            try {
                gitService.stage(sandboxRepoPath, ".");
                gitService.commit(
                        sandboxRepoPath,
                        commitMessage,
                        nickname,
                        nickname + "@myide.com"
                );
            } catch (Exception e) {
                System.out.println("커밋 건너뜀 (변경사항 없음): " + e.getMessage());
            }

            boolean isMergeSuccess = gitService.mergeBranchInto(
                    targetRepoPath,
                    sandboxBranch,
                    true
            );

            if (!isMergeSuccess) {
                return ResponseEntity
                        .status(409)
                        .body(Map.of(
                                "message", "충돌이 발생했습니다. 샌드박스가 보존되었습니다. 코드를 확인해주세요.",
                                "sandboxBranch", sandboxBranch,
                                "targetBranch", targetBranch
                        ));
            }

            String targetRevision = gitService.getHeadHash(targetRepoPath);

            System.out.println("[SandboxApply] " + targetBranch + " revision = " + targetRevision);

            gitService.deleteBranch(masterRepoPath, sandboxRepoPath, sandboxBranch);

            FileRequest eventRequest = new FileRequest();
            eventRequest.setWorkspaceId(workspaceId);
            eventRequest.setProjectName(projectName);
            eventRequest.setBranchName(targetBranch);
            eventRequest.setFilePath("");
            eventRequest.setType("sandbox");
            eventRequest.setNewName("");

            workspaceEventWebSocketHandler.broadcastFileTreeChanged(
                    eventRequest,
                    "SANDBOX_APPLIED",
                    targetRevision
            );

            return ResponseEntity.ok(
                    Map.of(
                            "message", "성공적으로 " + targetBranch + " 브랜치에 코드가 반영되었습니다.",
                            "targetBranch", targetBranch,
                            "revision", targetRevision
                    )
            );
        } catch (Exception e) {
            return ResponseEntity
                    .badRequest()
                    .body("병합 중 시스템 오류가 발생했습니다: " + e.getMessage());
        }
    }
}