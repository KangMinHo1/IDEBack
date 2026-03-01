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

    // 💡 [핵심] Java JGit 대신, 진짜 터미널 Git 명령어를 쳐주는 헬퍼 함수!
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
            // status는 변경사항이 있으면 0이 아닐 수도 있으므로 예외처리
            log.warn("Git 명령어 실패 [{}]: {}", Arrays.toString(commands), output.toString().trim());
            throw new RuntimeException(output.toString().trim());
        }
        return output.toString();
    }

    // 💡 변수명을 masterRepoPath 로 통일했습니다.
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

    // 💡 [핵심 수정] 병합 상태(isMerging)와 충돌 파일(conflicted)을 감지합니다!
    public Map<String, Object> getStatus(Path repoPath) {
        try {
            // 현재 병합 중인지 확인 (.git/MERGE_HEAD 파일이 존재하면 병합 중인 것임)
            boolean isMerging = Files.exists(repoPath.resolve(".git").resolve("MERGE_HEAD"));

            // core.quotepath=false 옵션은 한글 파일명이 \355\225... 처럼 깨지는 것을 막아줍니다!
            String output = executeGitCommand(repoPath, "git", "-c", "core.quotepath=false", "status", "--porcelain");

            List<Map<String, String>> staged = new ArrayList<>();
            List<Map<String, String>> unstaged = new ArrayList<>();
            List<Map<String, String>> conflicted = new ArrayList<>(); // 💡 충돌 파일 저장소

            if (output.trim().isEmpty()) {
                return Map.of("staged", staged, "unstaged", unstaged, "conflicted", conflicted, "isMerging", isMerging);
            }

            for (String line : output.split("\n")) {
                if (line.length() < 3) continue;
                String x = line.substring(0, 1);
                String y = line.substring(1, 2);
                String path = line.substring(3).trim();

                if (path.startsWith("\"") && path.endsWith("\"")) path = path.substring(1, path.length() - 1);

                // 💡 상태가 'U'가 포함되거나 'AA', 'DD'면 충돌(Conflict)이 발생한 파일입니다!
                if (x.equals("U") || y.equals("U") || (x.equals("A") && y.equals("A")) || (x.equals("D") && y.equals("D"))) {
                    conflicted.add(Map.of("path", path, "status", "conflicted"));
                    continue; // 충돌난 파일은 staged/unstaged에 넣지 않고 스킵합니다.
                }

                if (x.equals("A") || x.equals("C") || x.equals("R")) staged.add(Map.of("path", path, "status", "added"));
                else if (x.equals("M")) staged.add(Map.of("path", path, "status", "modified"));
                else if (x.equals("D")) staged.add(Map.of("path", path, "status", "deleted"));

                if (y.equals("M")) unstaged.add(Map.of("path", path, "status", "modified"));
                else if (y.equals("D")) unstaged.add(Map.of("path", path, "status", "deleted"));
                else if (x.equals("?") && y.equals("?")) unstaged.add(Map.of("path", path, "status", "added"));
            }

            // 💡 프론트엔드로 전부 묶어서 던져줍니다.
            return Map.of("staged", staged, "unstaged", unstaged, "conflicted", conflicted, "isMerging", isMerging);
        } catch (Exception e) {
            log.error("Git Status Failed", e);
            throw new RuntimeException("Git 상태 조회 실패: " + e.getMessage());
        }
    }

    public void stage(Path repoPath, String filePattern) {
        try {
            executeGitCommand(repoPath, "git", "add", filePattern);
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
            throw new RuntimeException("푸시 실패: 확인 후 다시 시도해주세요. (토큰 만료 또는 권한 부족일 수 있습니다.)");
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

    public void merge(Path repoPath, String targetBranch) {
        try {
            executeGitCommand(repoPath, "git", "merge", targetBranch);
            log.info("🔀 Merged branch '{}' into current branch.", targetBranch);
        } catch (Exception e) {
            throw new RuntimeException("Merge 실패 (충돌 Conflict 발생): " + e.getMessage());
        }
    }

    // 💡 [New] 병합 취소 (Abort) 메서드 추가!
    public void abortMerge(Path repoPath) {
        try {
            executeGitCommand(repoPath, "git", "merge", "--abort");
            log.info("🚫 Merge Aborted in {}", repoPath);
        } catch (Exception e) {
            throw new RuntimeException("병합 취소 실패: " + e.getMessage());
        }
    }

    // =========================================================================
    // 💡 [New] 순정 체크아웃 및 하드 리셋 메서드 추가
    // =========================================================================

    public void reset(Path repoPath, String targetHash) {
        try {
            // 작업 폴더를 아예 해당 해시 시점으로 강제 리셋합니다.
            executeGitCommand(repoPath, "git", "reset", "--hard", targetHash);
            log.info("⏪ Reset current branch to commit: {}", targetHash);
        } catch (Exception e) {
            throw new RuntimeException("Reset 실패: " + e.getMessage());
        }
    }

    public void checkoutCommit(Path repoPath, String targetHash) {
        try {
            // 특정 해시(Detached HEAD)로 가거나, 다시 원래 브랜치 이름(master 등)으로 돌아오기 둘 다 가능합니다!
            executeGitCommand(repoPath, "git", "checkout", targetHash);
            log.info("🎯 Checked out to target: {} in {}", targetHash, repoPath);
        } catch (Exception e) {
            throw new RuntimeException("체크아웃 실패 (저장하지 않은 변경사항이 있거나 충돌이 발생했습니다): " + e.getMessage());
        }
    }

    // =========================================================================
    // 💡 3. History (소스트리 스타일 그래프 정렬 옵션 추가!)
    // =========================================================================
    public List<Map<String, String>> getHistory(Path repoPath) {
        try {
            // 💡 [핵심 해결책] 명령어에 "master"를 명시적으로 추가하여 master 브랜치를 무조건 왼쪽 기둥으로 고정합니다!
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
}