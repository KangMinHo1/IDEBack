package com.myide.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Service
public class GitService {

    private static final String EXCLUDE_KEYWORD = "$$codemap$$";

    private String executeGitCommand(Path directory, String... commands) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0 && !commands[1].equals("status")) {
            String safeCommandLog = Arrays.toString(commands).replaceAll("://.*@", "://***@");
            String safeOutputLog = output.toString().trim().replaceAll("://.*@", "://***@");

            log.warn("Git 명령어 실패 [{}]: {}", safeCommandLog, safeOutputLog);
            throw new RuntimeException(safeOutputLog);
        }
        return output.toString();
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

    public void createWorktree(Path masterRepoPath, Path worktreePath, String branchName) {
        try {
            executeGitCommand(masterRepoPath, "git", "worktree", "add", "-b", branchName, worktreePath.toAbsolutePath().toString(), "HEAD");
            log.info("🌿 Worktree Created via CLI: {} -> {}", branchName, worktreePath);
        } catch (Exception e) {
            log.error("Worktree Create Failed", e);
            throw new RuntimeException("워크트리 생성 실패: " + e.getMessage());
        }
    }

    public Map<String, Object> getStatus(Path repoPath) {
        try {
            boolean isMerging = Files.exists(repoPath.resolve(".git").resolve("MERGE_HEAD"));
            String output = executeGitCommand(repoPath, "git", "-c", "core.quotepath=false", "status", "--porcelain");

            List<Map<String, String>> staged = new ArrayList<>();
            List<Map<String, String>> unstaged = new ArrayList<>();
            List<Map<String, String>> conflicted = new ArrayList<>();

            if (output.trim().isEmpty()) {
                return Map.of("staged", staged, "unstaged", unstaged, "conflicted", conflicted, "isMerging", isMerging);
            }

            for (String line : output.split("\n")) {
                if (line.length() < 3) continue;
                String x = line.substring(0, 1);
                String y = line.substring(1, 2);
                String path = line.substring(3).trim();

                if (path.startsWith("\"") && path.endsWith("\"")) path = path.substring(1, path.length() - 1);

                if (path.contains(EXCLUDE_KEYWORD)) continue;

                if (x.equals("U") || y.equals("U") || (x.equals("A") && y.equals("A")) || (x.equals("D") && y.equals("D"))) {
                    conflicted.add(Map.of("path", path, "status", "conflicted"));
                    continue;
                }

                if (x.equals("A") || x.equals("C") || x.equals("R")) staged.add(Map.of("path", path, "status", "added"));
                else if (x.equals("M")) staged.add(Map.of("path", path, "status", "modified"));
                else if (x.equals("D")) staged.add(Map.of("path", path, "status", "deleted"));

                if (y.equals("M")) unstaged.add(Map.of("path", path, "status", "modified"));
                else if (y.equals("D")) unstaged.add(Map.of("path", path, "status", "deleted"));
                else if (x.equals("?") && y.equals("?")) unstaged.add(Map.of("path", path, "status", "added"));
            }

            return Map.of("staged", staged, "unstaged", unstaged, "conflicted", conflicted, "isMerging", isMerging);
        } catch (Exception e) {
            log.error("Git Status Failed", e);
            throw new RuntimeException("Git 상태 조회 실패: " + e.getMessage());
        }
    }

    public void stage(Path repoPath, String filePattern) {
        try {
            if (".".equals(filePattern)) {
                log.info("🛡️ [Git] Stage All 요청 감지. 시스템 파일({}) 제외 처리를 시작합니다.", EXCLUDE_KEYWORD);
                String statusOutput = executeGitCommand(repoPath, "git", "status", "--porcelain");
                for (String line : statusOutput.split("\n")) {
                    if (line.length() < 3) continue;
                    String path = line.substring(3).trim();
                    if (path.startsWith("\"") && path.endsWith("\"")) path = path.substring(1, path.length() - 1);

                    if (!path.contains(EXCLUDE_KEYWORD)) {
                        executeGitCommand(repoPath, "git", "add", path);
                    }
                }
            } else {
                if (filePattern.contains(EXCLUDE_KEYWORD)) {
                    log.warn("🛡️ [Git] 시스템 파일({})은 Stage 할 수 없습니다.", filePattern);
                    return;
                }
                executeGitCommand(repoPath, "git", "add", filePattern);
            }
        } catch (Exception e) {
            throw new RuntimeException("Stage 실패: " + e.getMessage());
        }
    }

    public void unstage(Path repoPath, String filePattern) {
        try {
            executeGitCommand(repoPath, "git", "reset", "HEAD", filePattern);
        } catch (Exception e) {
            throw new RuntimeException("Unstage 실패: " + e.getMessage());
        }
    }

    public void commit(Path targetPath, String message, String authorName, String authorEmail) {
        try {
            executeGitCommand(targetPath, "git", "-c", "user.name=" + authorName, "-c", "user.email=" + authorEmail, "commit", "-m", message);
            log.info("✅ CLI Committed to {}: {} (By: {})", targetPath, message, authorName);
        } catch (Exception e) {
            log.error("Git Commit Failed", e);
            throw new RuntimeException("커밋 실패: " + e.getMessage());
        }
    }

    public void push(Path targetPath, String token) {
        try {
            String remoteUrl = executeGitCommand(targetPath, "git", "config", "--get", "remote.origin.url").trim();
            if (remoteUrl.isEmpty()) {
                throw new RuntimeException("원격 저장소(GitHub URL)가 연결되어 있지 않습니다. 먼저 연동해주세요.");
            }

            String pushUrl = remoteUrl;
            if (remoteUrl.startsWith("https://")) {
                pushUrl = remoteUrl.replace("https://", "https://" + token + "@");
            } else if (remoteUrl.startsWith("http://")) {
                pushUrl = remoteUrl.replace("http://", "http://" + token + "@");
            }

            String currentBranch = executeGitCommand(targetPath, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();

            log.info("🚀 Pushing branch '{}' to remote...", currentBranch);
            executeGitCommand(targetPath, "git", "push", pushUrl, currentBranch + ":" + currentBranch);
            log.info("✅ Push Completed Successfully!");

        } catch (Exception e) {
            log.error("Git Push Failed", e);
            throw new RuntimeException("푸시 실패: 확인 후 다시 시도해주세요.");
        }
    }

    public void pull(Path targetPath, String token) {
        try {
            String remoteUrl = executeGitCommand(targetPath, "git", "config", "--get", "remote.origin.url").trim();
            if (remoteUrl.isEmpty()) throw new RuntimeException("원격 저장소가 연동되어 있지 않습니다.");

            String pullUrl = remoteUrl;
            if (remoteUrl.startsWith("https://")) {
                pullUrl = remoteUrl.replace("https://", "https://" + token + "@");
            }

            String currentBranch = executeGitCommand(targetPath, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
            log.info("📥 Pulling branch '{}' from remote...", currentBranch);
            executeGitCommand(targetPath, "git", "pull", pullUrl, currentBranch);
            log.info("✅ Pull Completed Successfully!");
        } catch (Exception e) {
            log.error("Git Pull Failed", e);
            throw new RuntimeException("Pull 실패 (충돌 발생 또는 토큰 권한 문제): " + e.getMessage());
        }
    }

    // =========================================================================
    // 💡 [수정] 충돌 발생 여부를 boolean으로 반환하도록 변경 (true: 성공, false: 충돌)
    // =========================================================================
    public boolean merge(Path repoPath, String targetBranch) {
        try {
            String output = executeGitCommand(repoPath, "git", "merge", targetBranch);
            log.info("🔀 Merged branch '{}' into current branch. Output: {}", targetBranch, output);
            return true;
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.toLowerCase().contains("conflict") || errorMsg.contains("Automatic merge failed"))) {
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
            String output = executeGitCommand(repoPath, "git", "log", "master", "--all", "--graph", "--topo-order", "--date-order", "--color=never", "--pretty=format:|*|%h|*|%an|*|%ad|*|%s|*|%d", "--date=short");
            List<Map<String, String>> history = new ArrayList<>();

            if (output.trim().isEmpty()) return history;

            for (String line : output.split("\n")) {
                Map<String, String> commit = new HashMap<>();

                if (line.contains("|*|")) {
                    String[] parts = line.split("\\|\\*\\|", -1);
                    commit.put("graph", parts[0]);
                    commit.put("hash", parts.length > 1 ? parts[1].trim() : "");
                    commit.put("author", parts.length > 2 ? parts[2].trim() : "");
                    commit.put("date", parts.length > 3 ? parts[3].trim() : "");
                    commit.put("message", parts.length > 4 ? parts[4].trim() : "");
                    String refs = parts.length > 5 ? parts[5].trim().replaceAll("[\\(\\)]", "") : "";
                    commit.put("refs", refs);
                }
                else {
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

    // 💡 [최종 수정] 윈도우의 읽기 전용(Read-Only) 파일까지 강제로 부수고 삭제하는 불도저 로직!
    public void deleteBranch(Path masterRepoPath, Path worktreePath, String branchName) {
        try {
            if ("master".equalsIgnoreCase(branchName)) {
                throw new IllegalArgumentException("master 브랜치는 삭제할 수 없습니다.");
            }

            // 1. 워크트리 제거 명령 (정상적인 워크트리일 경우)
            try {
                executeGitCommand(masterRepoPath, "git", "worktree", "remove", "-f", worktreePath.toAbsolutePath().toString());
                log.info("🗑️ Worktree Folder Deleted: {}", worktreePath);
            } catch (Exception e) {
                log.warn("Git worktree remove 실패 (찌꺼기 폴더일 가능성 높음). 강제 삭제 진입...");
            }

            // 2. 🚨 [핵심] 윈도우 Read-Only 파일 무시하고 강제로 폴더 삭제
            if (Files.exists(worktreePath)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(worktreePath)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    p.toFile().setWritable(true); // 읽기 전용 해제 (Windows Git 폴더 삭제의 핵심!)
                                    Files.delete(p);
                                } catch (Exception ignored) {
                                    // 삭제 실패해도 무시하고 다음 파일 진행
                                }
                            });
                } catch (Exception e) {
                    log.warn("물리적 폴더 강제 삭제 중 일부 실패: {}", e.getMessage());
                }
            }

            // 3. 브랜치 기록 삭제
            try {
                executeGitCommand(masterRepoPath, "git", "branch", "-D", branchName);
                log.info("🗑️ Git Branch Deleted: {}", branchName);
            } catch (Exception e) {
                log.warn("Git branch 삭제 실패 (이미 지워졌거나 존재하지 않음)");
            }

        } catch (Exception e) {
            log.error("Branch Delete Failed", e);
            throw new RuntimeException("브랜치 삭제 로직 에러: " + e.getMessage());
        }
    }
}