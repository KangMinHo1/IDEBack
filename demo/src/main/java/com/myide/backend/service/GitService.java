package com.myide.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class GitService {

    private static final String EXCLUDE_KEYWORD = "$$codemap$$";
    private static final Set<String> PROTECTED_BRANCHES = Set.of("master", "main");

    private static class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private boolean isProtectedBranch(String branchName) {
        return branchName != null
                && PROTECTED_BRANCHES.contains(branchName.toLowerCase(Locale.ROOT));
    }

    private String maskSecret(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("://.*@", "://***@");
    }

    private CommandResult runGitCommand(Path directory, String... commands) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        return new CommandResult(exitCode, output.toString());
    }

    private String executeGitCommand(Path directory, String... commands) throws Exception {
        CommandResult result = runGitCommand(directory, commands);
        boolean allowStatusFailure = Arrays.asList(commands).contains("status");

        if (result.exitCode != 0 && !allowStatusFailure) {
            String safeCommandLog = maskSecret(Arrays.toString(commands));
            String safeOutputLog = maskSecret(result.output.trim());

            log.warn("Git 명령어 실패 [{}]: {}", safeCommandLog, safeOutputLog);

            throw new RuntimeException(safeOutputLog);
        }

        return result.output;
    }

    public void createRepository(Path masterRepoPath) {
        try {
            try (Git git = Git.init().setDirectory(masterRepoPath.toFile()).call()) {
                log.info("🐙 Git Init Completed: {}", masterRepoPath);

                StoredConfig config = git.getRepository().getConfig();
                config.setString("user", null, "name", "WebIDE-Bot");
                config.setString("user", null, "email", "bot@webide.com");
                config.save();

                git.add().addFilepattern(".").call();
                git.commit().setMessage("Initial commit: Project Created").setSign(false).call();

                log.info("✅ Initial Commit Completed.");
            }
        } catch (Exception e) {
            log.error("Git Init Failed", e);
            throw new RuntimeException("Git 저장소 생성 실패: " + e.getMessage());
        }
    }

    public void addRemote(Path repoPath, String gitUrl) {
        try (Git git = Git.open(repoPath.toFile())) {
            RemoteAddCommand remoteAdd = git.remoteAdd();
            remoteAdd.setName("origin");
            remoteAdd.setUri(new URIish(gitUrl));
            remoteAdd.call();

            log.info("🔗 Git Remote Added: {} -> {}", repoPath, gitUrl);
        } catch (Exception e) {
            log.error("Git Remote Add Failed", e);
        }
    }

    /*
     * 기존 호출 호환용 메서드입니다.
     * 다음 ProjectService 수정 후에는 baseBranch를 넘기는 메서드를 사용합니다.
     */
    public void createWorktree(Path masterRepoPath, Path worktreePath, String branchName) {
        createWorktree(masterRepoPath, worktreePath, branchName, "master");
    }

    /*
     * Sourcetree/Git Flow 방식 브랜치 생성입니다.
     *
     * 예:
     * - baseBranch = develop
     * - branchName = feature/login
     *
     * 실행:
     * git worktree add -b feature/login <worktreePath> refs/heads/develop
     */
    public void createWorktree(
            Path masterRepoPath,
            Path worktreePath,
            String branchName,
            String baseBranch
    ) {
        try {
            String normalizedBaseBranch =
                    baseBranch == null || baseBranch.trim().isEmpty()
                            ? "master"
                            : baseBranch.trim();

            String baseRef =
                    "HEAD".equalsIgnoreCase(normalizedBaseBranch)
                            ? "HEAD"
                            : "refs/heads/" + normalizedBaseBranch;

            executeGitCommand(
                    masterRepoPath,
                    "git",
                    "worktree",
                    "add",
                    "-b",
                    branchName,
                    worktreePath.toAbsolutePath().toString(),
                    baseRef
            );

            log.info(
                    "🌿 Worktree Created via CLI: {} from {} -> {}",
                    branchName,
                    normalizedBaseBranch,
                    worktreePath
            );
        } catch (Exception e) {
            log.error("Worktree Create Failed", e);
            throw new RuntimeException("워크트리 생성 실패: " + e.getMessage());
        }
    }

    public List<String> getLocalBranches(Path repoPath) {
        try {
            String output = executeGitCommand(
                    repoPath,
                    "git",
                    "for-each-ref",
                    "--format=%(refname:short)",
                    "refs/heads"
            );

            List<String> branches = new ArrayList<>();

            for (String line : output.split("\n")) {
                String branchName = line.trim();

                if (!branchName.isEmpty()) {
                    branches.add(branchName);
                }
            }

            branches.sort((a, b) -> {
                int priorityA = getBranchPriority(a);
                int priorityB = getBranchPriority(b);

                if (priorityA != priorityB) {
                    return Integer.compare(priorityA, priorityB);
                }

                return a.compareToIgnoreCase(b);
            });

            return branches;
        } catch (Exception e) {
            throw new RuntimeException("브랜치 목록 조회 실패: " + e.getMessage());
        }
    }

    private int getBranchPriority(String branchName) {
        if ("master".equalsIgnoreCase(branchName)) return 0;
        if ("main".equalsIgnoreCase(branchName)) return 1;
        if ("develop".equalsIgnoreCase(branchName)) return 2;
        if (branchName != null && branchName.startsWith("feature/")) return 3;
        if (branchName != null && branchName.startsWith("release/")) return 4;
        if (branchName != null && branchName.startsWith("hotfix/")) return 5;
        return 6;
    }

    public boolean branchExists(Path repoPath, String branchName) {
        try {
            CommandResult result = runGitCommand(
                    repoPath,
                    "git",
                    "show-ref",
                    "--verify",
                    "--quiet",
                    "refs/heads/" + branchName
            );

            return result.exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isWorkingTreeClean(Path repoPath) {
        try {
            String output = executeGitCommand(
                    repoPath,
                    "git",
                    "-c",
                    "core.quotepath=false",
                    "status",
                    "--porcelain"
            );

            for (String line : output.split("\n")) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                String path = line.length() >= 3 ? line.substring(3).trim() : line.trim();

                if (path.contains(EXCLUDE_KEYWORD)) {
                    continue;
                }

                return false;
            }

            return true;
        } catch (Exception e) {
            throw new RuntimeException("작업 트리 상태 확인 실패: " + e.getMessage());
        }
    }

    private boolean isMergeInProgress(Path repoPath) {
        try {
            String mergeHeadPath = executeGitCommand(
                    repoPath,
                    "git",
                    "rev-parse",
                    "--git-path",
                    "MERGE_HEAD"
            ).trim();

            if (mergeHeadPath.isEmpty()) {
                return false;
            }

            Path path = Path.of(mergeHeadPath);

            if (!path.isAbsolute()) {
                path = repoPath.resolve(path).normalize();
            }

            return Files.exists(path);
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> getStatus(Path repoPath) {
        try {
            boolean isMerging = isMergeInProgress(repoPath);

            String output = executeGitCommand(
                    repoPath,
                    "git",
                    "-c",
                    "core.quotepath=false",
                    "status",
                    "--porcelain"
            );

            List<Map<String, String>> staged = new ArrayList<>();
            List<Map<String, String>> unstaged = new ArrayList<>();
            List<Map<String, String>> conflicted = new ArrayList<>();

            if (output.trim().isEmpty()) {
                return Map.of(
                        "staged", staged,
                        "unstaged", unstaged,
                        "conflicted", conflicted,
                        "isMerging", isMerging
                );
            }

            for (String line : output.split("\n")) {
                if (line.length() < 3) continue;

                String x = line.substring(0, 1);
                String y = line.substring(1, 2);
                String path = line.substring(3).trim();

                if (path.startsWith("\"") && path.endsWith("\"")) {
                    path = path.substring(1, path.length() - 1);
                }

                if (path.contains(EXCLUDE_KEYWORD)) {
                    continue;
                }

                if (x.equals("U")
                        || y.equals("U")
                        || (x.equals("A") && y.equals("A"))
                        || (x.equals("D") && y.equals("D"))) {

                    conflicted.add(Map.of("path", path, "status", "conflicted"));
                    continue;
                }

                if (x.equals("A") || x.equals("C") || x.equals("R")) {
                    staged.add(Map.of("path", path, "status", "added"));
                } else if (x.equals("M")) {
                    staged.add(Map.of("path", path, "status", "modified"));
                } else if (x.equals("D")) {
                    staged.add(Map.of("path", path, "status", "deleted"));
                }

                if (y.equals("M")) {
                    unstaged.add(Map.of("path", path, "status", "modified"));
                } else if (y.equals("D")) {
                    unstaged.add(Map.of("path", path, "status", "deleted"));
                } else if (x.equals("?") && y.equals("?")) {
                    unstaged.add(Map.of("path", path, "status", "added"));
                }
            }

            return Map.of(
                    "staged", staged,
                    "unstaged", unstaged,
                    "conflicted", conflicted,
                    "isMerging", isMerging
            );
        } catch (Exception e) {
            log.error("Git Status Failed", e);
            throw new RuntimeException("Git 상태 조회 실패: " + e.getMessage());
        }
    }

    public void stage(Path repoPath, String filePattern) {
        try {
            if (".".equals(filePattern)) {
                log.info("🛡️ [Git] Stage All 요청 감지. 시스템 파일({}) 제외 처리를 시작합니다.", EXCLUDE_KEYWORD);

                String statusOutput = executeGitCommand(
                        repoPath,
                        "git",
                        "-c",
                        "core.quotepath=false",
                        "status",
                        "--porcelain"
                );

                for (String line : statusOutput.split("\n")) {
                    if (line.length() < 3) continue;

                    String path = line.substring(3).trim();

                    if (path.startsWith("\"") && path.endsWith("\"")) {
                        path = path.substring(1, path.length() - 1);
                    }

                    if (!path.contains(EXCLUDE_KEYWORD)) {
                        executeGitCommand(repoPath, "git", "add", "--", path);
                    }
                }
            } else {
                if (filePattern.contains(EXCLUDE_KEYWORD)) {
                    log.warn("🛡️ [Git] 시스템 파일({})은 Stage 할 수 없습니다.", filePattern);
                    return;
                }

                executeGitCommand(repoPath, "git", "add", "--", filePattern);
            }
        } catch (Exception e) {
            throw new RuntimeException("Stage 실패: " + e.getMessage());
        }
    }

    public void unstage(Path repoPath, String filePattern) {
        try {
            executeGitCommand(repoPath, "git", "reset", "HEAD", "--", filePattern);
        } catch (Exception e) {
            throw new RuntimeException("Unstage 실패: " + e.getMessage());
        }
    }

    public void commit(Path targetPath, String message, String authorName, String authorEmail) {
        try {
            executeGitCommand(
                    targetPath,
                    "git",
                    "-c",
                    "user.name=" + authorName,
                    "-c",
                    "user.email=" + authorEmail,
                    "commit",
                    "-m",
                    message
            );

            log.info("✅ CLI Committed to {}: {} (By: {})", targetPath, message, authorName);
        } catch (Exception e) {
            log.error("Git Commit Failed", e);
            throw new RuntimeException("커밋 실패: " + e.getMessage());
        }
    }

    private String buildAuthenticatedRemoteUrl(String remoteUrl, String token) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new RuntimeException("원격 저장소가 연결되어 있지 않습니다.");
        }

        if (token == null || token.trim().isEmpty()) {
            return remoteUrl;
        }

        if (remoteUrl.startsWith("https://")) {
            return remoteUrl.replace("https://", "https://" + token + "@");
        }

        if (remoteUrl.startsWith("http://")) {
            return remoteUrl.replace("http://", "http://" + token + "@");
        }

        return remoteUrl;
    }

    public void fetch(Path targetPath, String token) {
        try {
            String remoteUrl = executeGitCommand(
                    targetPath,
                    "git",
                    "config",
                    "--get",
                    "remote.origin.url"
            ).trim();

            if (remoteUrl.isEmpty()) {
                throw new RuntimeException("원격 저장소가 연동되어 있지 않습니다.");
            }

            String fetchUrl = buildAuthenticatedRemoteUrl(remoteUrl, token);

            executeGitCommand(targetPath, "git", "fetch", "--prune", fetchUrl);

            log.info("📡 Fetch Completed: {}", targetPath);
        } catch (Exception e) {
            log.error("Git Fetch Failed", e);
            throw new RuntimeException("Fetch 실패: " + e.getMessage());
        }
    }

    public void push(Path targetPath, String token) {
        try {
            String remoteUrl = executeGitCommand(
                    targetPath,
                    "git",
                    "config",
                    "--get",
                    "remote.origin.url"
            ).trim();

            if (remoteUrl.isEmpty()) {
                throw new RuntimeException("원격 저장소(GitHub URL)가 연결되어 있지 않습니다. 먼저 연동해주세요.");
            }

            String pushUrl = buildAuthenticatedRemoteUrl(remoteUrl, token);

            String currentBranch = executeGitCommand(
                    targetPath,
                    "git",
                    "rev-parse",
                    "--abbrev-ref",
                    "HEAD"
            ).trim();

            log.info("🚀 Pushing branch '{}' to remote...", currentBranch);

            executeGitCommand(
                    targetPath,
                    "git",
                    "push",
                    pushUrl,
                    currentBranch + ":" + currentBranch
            );

            log.info("✅ Push Completed Successfully!");
        } catch (Exception e) {
            log.error("Git Push Failed", e);
            throw new RuntimeException("푸시 실패: " + e.getMessage());
        }
    }

    public void pushBranch(Path targetPath, String token, String branchName, boolean setUpstream) {
        try {
            String remoteUrl = executeGitCommand(
                    targetPath,
                    "git",
                    "config",
                    "--get",
                    "remote.origin.url"
            ).trim();

            if (remoteUrl.isEmpty()) {
                throw new RuntimeException("원격 저장소(GitHub URL)가 연결되어 있지 않습니다. 먼저 연동해주세요.");
            }

            String pushUrl = buildAuthenticatedRemoteUrl(remoteUrl, token);

            if (setUpstream) {
                executeGitCommand(
                        targetPath,
                        "git",
                        "push",
                        "-u",
                        pushUrl,
                        branchName + ":" + branchName
                );
            } else {
                executeGitCommand(
                        targetPath,
                        "git",
                        "push",
                        pushUrl,
                        branchName + ":" + branchName
                );
            }

            log.info("✅ Push Branch Completed: {}", branchName);
        } catch (Exception e) {
            log.error("Git Push Branch Failed", e);
            throw new RuntimeException("푸시 실패: " + e.getMessage());
        }
    }

    public void pull(Path targetPath, String token, String authorName, String authorEmail) {
        try {
            String remoteUrl = executeGitCommand(
                    targetPath,
                    "git",
                    "config",
                    "--get",
                    "remote.origin.url"
            ).trim();

            if (remoteUrl.isEmpty()) {
                throw new RuntimeException("원격 저장소가 연동되어 있지 않습니다.");
            }

            String pullUrl = buildAuthenticatedRemoteUrl(remoteUrl, token);

            String currentBranch = executeGitCommand(
                    targetPath,
                    "git",
                    "rev-parse",
                    "--abbrev-ref",
                    "HEAD"
            ).trim();

            log.info("📥 Pulling branch '{}' from remote...", currentBranch);

            executeGitCommand(
                    targetPath,
                    "git",
                    "-c",
                    "user.name=" + authorName,
                    "-c",
                    "user.email=" + authorEmail,
                    "pull",
                    pullUrl,
                    currentBranch,
                    "--allow-unrelated-histories"
            );

            log.info("✅ Pull Completed Successfully!");
        } catch (Exception e) {
            log.error("Git Pull Failed", e);
            throw new RuntimeException("Pull 실패 (충돌 발생 또는 권한 문제): " + e.getMessage());
        }
    }

    /*
     * 기존 API 호환용 메서드입니다.
     *
     * 의미:
     * - 현재 repoPath 브랜치에 targetBranch를 병합합니다.
     */
    public boolean merge(Path repoPath, String targetBranch) {
        return mergeBranchInto(repoPath, targetBranch, false);
    }

    /*
     * Sourcetree식 병합의 내부 실행 메서드입니다.
     *
     * targetRepoPath:
     * - 병합을 받을 브랜치의 worktree 경로
     *
     * sourceBranch:
     * - 병합할 브랜치명
     *
     * noFastForward:
     * - true면 git merge --no-ff --no-edit sourceBranch
     */
    public boolean mergeBranchInto(
            Path targetRepoPath,
            String sourceBranch,
            boolean noFastForward
    ) {
        try {
            if (isMergeInProgress(targetRepoPath)) {
                throw new RuntimeException("이미 병합이 진행 중입니다. 먼저 병합을 완료하거나 취소해주세요.");
            }

            if (!isWorkingTreeClean(targetRepoPath)) {
                throw new RuntimeException("병합 대상 브랜치에 커밋하지 않은 변경사항이 있습니다. 먼저 커밋하거나 변경사항을 정리해주세요.");
            }

            String output;

            if (noFastForward) {
                output = executeGitCommand(
                        targetRepoPath,
                        "git",
                        "merge",
                        "--no-ff",
                        "--no-edit",
                        sourceBranch
                );
            } else {
                output = executeGitCommand(
                        targetRepoPath,
                        "git",
                        "merge",
                        "--no-edit",
                        sourceBranch
                );
            }

            log.info("🔀 Merged branch '{}' into target repo. Output: {}", sourceBranch, output);

            return true;
        } catch (Exception e) {
            String errorMsg = e.getMessage();

            if (errorMsg != null
                    && (errorMsg.toLowerCase(Locale.ROOT).contains("conflict")
                    || errorMsg.contains("Automatic merge failed")
                    || errorMsg.toLowerCase(Locale.ROOT).contains("fix conflicts"))) {

                log.warn("🔀 병합 중 충돌(Conflict) 발생! 프론트엔드가 제어하도록 false를 반환합니다.");

                return false;
            }

            throw new RuntimeException("Merge 실패: " + errorMsg);
        }
    }

    public void abortMerge(Path repoPath) {
        try {
            executeGitCommand(repoPath, "git", "merge", "--abort");

            log.info("🚫 Merge Aborted in {}", repoPath);
        } catch (Exception e) {
            throw new RuntimeException("병합 취소 실패: " + e.getMessage());
        }
    }

    public void reset(Path repoPath, String targetHash) {
        try {
            executeGitCommand(repoPath, "git", "reset", "--hard", targetHash);

            log.info("⏪ Reset current branch to commit: {}", targetHash);
        } catch (Exception e) {
            throw new RuntimeException("Reset 실패: " + e.getMessage());
        }
    }

    public void checkoutCommit(Path repoPath, String targetHash) {
        try {
            executeGitCommand(repoPath, "git", "checkout", targetHash);

            log.info("🎯 Checked out to target: {} in {}", targetHash, repoPath);
        } catch (Exception e) {
            throw new RuntimeException("체크아웃 실패: " + e.getMessage());
        }
    }

    public List<Map<String, String>> getHistory(Path repoPath) {
        try {
            try {
                executeGitCommand(repoPath, "git", "fetch", "origin");
            } catch (Exception e) {
                log.warn("Fetch 실패. 원격 저장소가 없거나 통신 오류일 수 있으므로 로컬 정보만 사용합니다.");
            }

            String output = executeGitCommand(
                    repoPath,
                    "git",
                    "log",
                    "--all",
                    "--graph",
                    "--topo-order",
                    "--date-order",
                    "--color=never",
                    "--pretty=format:|*|%h|*|%an|*|%ad|*|%s|*|%d",
                    "--date=short"
            );

            List<Map<String, String>> history = new ArrayList<>();

            if (output.trim().isEmpty()) {
                return history;
            }

            for (String line : output.split("\n")) {
                Map<String, String> commit = new HashMap<>();

                if (line.contains("|*|")) {
                    String[] parts = line.split("\\|\\*\\|", -1);

                    commit.put("graph", parts[0]);
                    commit.put("hash", parts.length > 1 ? parts[1].trim() : "");
                    commit.put("author", parts.length > 2 ? parts[2].trim() : "");
                    commit.put("date", parts.length > 3 ? parts[3].trim() : "");
                    commit.put("message", parts.length > 4 ? parts[4].trim() : "");

                    String refs = parts.length > 5
                            ? parts[5].trim().replaceAll("[\\(\\)]", "")
                            : "";

                    commit.put("refs", refs);
                } else {
                    commit.put("graph", line);
                    commit.put("hash", "");
                    commit.put("author", "");
                    commit.put("date", "");
                    commit.put("message", "");
                    commit.put("refs", "");
                }

                history.add(commit);
            }

            return history;
        } catch (Exception e) {
            throw new RuntimeException("히스토리 조회 실패: " + e.getMessage());
        }
    }

    public void deleteBranch(Path masterRepoPath, Path worktreePath, String branchName) {
        try {
            if (isProtectedBranch(branchName)) {
                throw new IllegalArgumentException("기본 브랜치는 삭제할 수 없습니다.");
            }

            try {
                executeGitCommand(
                        masterRepoPath,
                        "git",
                        "worktree",
                        "remove",
                        "-f",
                        "--",
                        worktreePath.toAbsolutePath().toString()
                );

                log.info("🗑️ Worktree Folder Deleted: {}", worktreePath);
            } catch (Exception e) {
                log.warn("Git worktree remove 실패. 물리 폴더 강제 삭제를 시도합니다.");
            }

            if (Files.exists(worktreePath)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(worktreePath)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    path.toFile().setWritable(true);
                                    Files.delete(path);
                                } catch (Exception ignored) {
                                }
                            });
                } catch (Exception e) {
                    log.warn("물리적 폴더 강제 삭제 중 일부 실패: {}", e.getMessage());
                }
            }

            try {
                executeGitCommand(
                        masterRepoPath,
                        "git",
                        "branch",
                        "-D",
                        "--",
                        branchName
                );

                log.info("🗑️ Git Branch Deleted: {}", branchName);
            } catch (Exception e) {
                log.warn("Git branch 삭제 실패. 이미 삭제되었거나 존재하지 않을 수 있습니다.");
            }
        } catch (Exception e) {
            log.error("Branch Delete Failed", e);
            throw new RuntimeException("브랜치 삭제 로직 에러: " + e.getMessage());
        }
    }

    public String getHeadHash(Path repoPath) {
        try {
            return executeGitCommand(
                    repoPath,
                    "git",
                    "rev-parse",
                    "--short",
                    "HEAD"
            ).trim();
        } catch (Exception e) {
            throw new RuntimeException("HEAD hash 조회 실패: " + e.getMessage());
        }
    }
}