package com.myide.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;

@Slf4j
@Service
public class GitService {

    // 1. 프로젝트 초기화 (git init + 사용자 설정 + 첫 커밋)
    public void createRepository(Path mainRepoPath) {
        try {
            // git init
            try (Git git = Git.init().setDirectory(mainRepoPath.toFile()).call()) {
                log.info("🐙 Git Init Completed: {}", mainRepoPath);

                // [필수] 커밋을 위한 가상의 사용자 정보 설정 (없으면 커밋 실패함)
                StoredConfig config = git.getRepository().getConfig();
                config.setString("user", null, "name", "WebIDE-Bot");
                config.setString("user", null, "email", "bot@webide.com");
                config.save();

                // git add .
                git.add().addFilepattern(".").call();

                // git commit
                git.commit().setMessage("Initial commit: Project Created").setSign(false).call();

                log.info("✅ Initial Commit Completed.");
            }
        } catch (Exception e) {
            log.error("Git Init Failed", e);
            throw new RuntimeException("Git 저장소 생성 실패: " + e.getMessage());
        }
    }

    // 2. [New] 원격 저장소 연결 (git remote add origin)
    public void addRemote(Path repoPath, String gitUrl) {
        try (Git git = Git.open(repoPath.toFile())) {
            // 기존 remote가 있으면 에러가 날 수 있으므로 확인하거나 덮어쓰기 로직 필요하지만,
            // 여기서는 심플하게 추가 시도 (필요시 try-catch로 무시 가능)
            RemoteAddCommand remoteAdd = git.remoteAdd();
            remoteAdd.setName("origin");
            remoteAdd.setUri(new URIish(gitUrl));
            remoteAdd.call();

            log.info("🔗 Git Remote Added: {} -> {}", repoPath, gitUrl);
        } catch (Exception e) {
            log.error("Git Remote Add Failed", e);
            // 이미 존재한다는 에러일 수 있음 -> 넘어가거나 로그만 남김
            // throw new RuntimeException("원격 저장소 연결 실패: " + e.getMessage());
        }
    }

    // 3. 커밋 (JGit 사용)
    public void commit(Path targetPath, String message) {
        try (Git git = Git.open(targetPath.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).setSign(false).call();
            log.info("✅ Committed to {}: {}", targetPath, message);
        } catch (Exception e) {
            log.error("Git Commit Failed", e);
            throw new RuntimeException("커밋 실패: " + e.getMessage());
        }
    }

    // 4. 워크트리 생성 (ProcessBuilder 사용 - CLI가 안정적)
    public void createWorktree(Path mainRepoPath, Path worktreePath, String branchName) {
        try {
            // 명령어: git worktree add -b {브랜치명} {경로} HEAD
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "worktree", "add",
                    "-b", branchName,
                    worktreePath.toAbsolutePath().toString(),
                    "HEAD"
            );

            pb.directory(mainRepoPath.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[Git Worktree Output]: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                // 이미 존재하는 브랜치일 경우 등 예외 처리
                throw new RuntimeException("Git worktree 명령 실패 (Exit Code: " + exitCode + ")");
            }

            log.info("🌿 Worktree Created via CLI: {} -> {}", branchName, worktreePath);

        } catch (Exception e) {
            log.error("Worktree Create Failed", e);
            throw new RuntimeException("워크트리 생성 실패: " + e.getMessage());
        }
    }
}