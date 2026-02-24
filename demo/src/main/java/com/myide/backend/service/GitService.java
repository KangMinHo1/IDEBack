package com.myide.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

    public void createRepository(Path mainRepoPath) {
        try {
            try (Git git = Git.init().setDirectory(mainRepoPath.toFile()).call()) {
                log.info("🐙 Git Init Completed: {}", mainRepoPath);
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

    public void createWorktree(Path mainRepoPath, Path worktreePath, String branchName) {
        try {
            executeGitCommand(mainRepoPath, "git", "worktree", "add", "-b", branchName, worktreePath.toAbsolutePath().toString(), "HEAD");
            log.info("🌿 Worktree Created via CLI: {} -> {}", branchName, worktreePath);
        } catch (Exception e) {
            log.error("Worktree Create Failed", e);
            throw new RuntimeException("워크트리 생성 실패: " + e.getMessage());
        }
    }

    // =========================================================================
    // 💡 [완벽 해결] 대시보드 API를 JGit 대신 100% Native Git CLI로 교체!
    // =========================================================================

    public Map<String, Object> getStatus(Path repoPath) {
        try {
            // core.quotepath=false 옵션은 한글 파일명이 \355\225... 처럼 깨지는 것을 막아줍니다!
            String output = executeGitCommand(repoPath, "git", "-c", "core.quotepath=false", "status", "--porcelain");

            List<Map<String, String>> staged = new ArrayList<>();
            List<Map<String, String>> unstaged = new ArrayList<>();

            if (output.trim().isEmpty()) {
                return Map.of("staged", staged, "unstaged", unstaged);
            }

            for (String line : output.split("\n")) {
                if (line.length() < 3) continue;
                String x = line.substring(0, 1);
                String y = line.substring(1, 2);
                String path = line.substring(3).trim();

                // 앞뒤 쌍따옴표 제거 (경로명)
                if (path.startsWith("\"") && path.endsWith("\"")) path = path.substring(1, path.length() - 1);

                // Staged 상태 분류 (왼쪽 글자)
                if (x.equals("A") || x.equals("C") || x.equals("R")) staged.add(Map.of("path", path, "status", "added"));
                else if (x.equals("M")) staged.add(Map.of("path", path, "status", "modified"));
                else if (x.equals("D")) staged.add(Map.of("path", path, "status", "deleted"));

                // Unstaged 상태 분류 (오른쪽 글자)
                if (y.equals("M")) unstaged.add(Map.of("path", path, "status", "modified"));
                else if (y.equals("D")) unstaged.add(Map.of("path", path, "status", "deleted"));
                else if (x.equals("?") && y.equals("?")) unstaged.add(Map.of("path", path, "status", "added")); // Untracked
            }

            return Map.of("staged", staged, "unstaged", unstaged);
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
            // 작성자 정보(Author)를 명령어에 직접 주입하여 커밋합니다!
            executeGitCommand(targetPath, "git", "-c", "user.name=" + authorName, "-c", "user.email=" + authorEmail, "commit", "-m", message);
            log.info("✅ CLI Committed to {}: {} (By: {})", targetPath, message, authorName);
        } catch (Exception e) {
            log.error("Git Commit Failed", e);
            throw new RuntimeException("커밋 실패: " + e.getMessage());
        }
    }

    // 💡 [New] 대망의 원격 저장소 Push 기능!
    public void push(Path targetPath, String token) {
        try {
            // 1. 현재 저장소에 설정된 원격 URL(origin)을 가져옵니다.
            String remoteUrl = executeGitCommand(targetPath, "git", "config", "--get", "remote.origin.url").trim();
            if (remoteUrl.isEmpty()) {
                throw new RuntimeException("원격 저장소(GitHub URL)가 연결되어 있지 않습니다. 먼저 연동해주세요.");
            }

            // 2. URL에 토큰을 안전하게 주입합니다. (https://토큰@github.com/...)
            String pushUrl = remoteUrl;
            if (remoteUrl.startsWith("https://")) {
                pushUrl = remoteUrl.replace("https://", "https://" + token + "@");
            } else if (remoteUrl.startsWith("http://")) {
                pushUrl = remoteUrl.replace("http://", "http://" + token + "@");
            }

            // 3. 현재 자신이 위치한 진짜 브랜치 이름을 알아냅니다 (예: master, ㄴㄴㄴ 등)
            String currentBranch = executeGitCommand(targetPath, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();

            // 4. 진짜 Git Push 명령어를 실행합니다!
            log.info("🚀 Pushing branch '{}' to remote...", currentBranch);
            executeGitCommand(targetPath, "git", "push", pushUrl, currentBranch + ":" + currentBranch);
            log.info("✅ Push Completed Successfully!");

        } catch (Exception e) {
            log.error("Git Push Failed", e);
            throw new RuntimeException("푸시 실패: 확인 후 다시 시도해주세요. (토큰 만료 또는 권한 부족일 수 있습니다.)");
        }
    }

    // 💡 1. Pull (원격 저장소에서 당겨오기 - Push처럼 토큰 주입 필요)
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

    // 💡 2. Merge (다른 브랜치를 현재 브랜치로 병합)
    public void merge(Path repoPath, String targetBranch) {
        try {
            executeGitCommand(repoPath, "git", "merge", targetBranch);
            log.info("🔀 Merged branch '{}' into current branch.", targetBranch);
        } catch (Exception e) {
            throw new RuntimeException("Merge 실패 (충돌 Conflict 발생): " + e.getMessage());
        }
    }

    // 💡 3. History (소스트리처럼 로그 가져오기)
    public List<Map<String, String>> getHistory(Path repoPath) {
        try {
            // 구분자를 |*| 로 설정해서 파싱 오류를 최소화합니다.
            // 출력 포맷: 해시 |*| 작성자 |*| 날짜 |*| 커밋메시지 |*| 브랜치(태그) 정보
            String output = executeGitCommand(repoPath, "git", "log", "--all", "--pretty=format:%h|*|%an|*|%ad|*|%s|*|%d", "--date=short");
            List<Map<String, String>> history = new ArrayList<>();

            if (output.trim().isEmpty()) return history;

            for (String line : output.split("\n")) {
                String[] parts = line.split("\\|\\*\\|");
                if (parts.length >= 4) {
                    Map<String, String> commit = new HashMap<>();
                    commit.put("hash", parts[0].trim());
                    commit.put("author", parts[1].trim());
                    commit.put("date", parts[2].trim());
                    commit.put("message", parts[3].trim());
                    // 괄호 (HEAD -> main, origin/main) 등을 제거하고 깔끔하게 전달
                    String refs = parts.length == 5 ? parts[4].trim().replaceAll("[\\(\\)]", "") : "";
                    commit.put("refs", refs);
                    history.add(commit);
                }
            }
            return history;
        } catch (Exception e) {
            throw new RuntimeException("히스토리 조회 실패: " + e.getMessage());
        }
    }
}