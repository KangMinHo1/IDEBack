package com.myide.backend.service.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 폴더째 복사한 워크스페이스의 브랜치 폴더(git worktree)가 원본 저장소가 아니라
 * 사본 저장소를 쓰는지 진짜 git 으로 확인한다.
 *
 * 레이아웃은 WorkspaceService 규칙 그대로: {root}/{project}/master 가 저장소,
 * 다른 브랜치는 {root}/{project}/{URL 인코딩한 브랜치명} 의 worktree.
 *
 * git 이 없는 환경에서는 건너뛴다.
 */
class GitWorktreeRelinkerTest {

    @TempDir
    Path temp;

    private Path originalRoot;
    private Path duplicateRoot;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(gitAvailable(), "git 이 설치되어 있지 않아 건너뜁니다.");

        originalRoot = temp.resolve("원본 프로젝트");
        duplicateRoot = temp.resolve("원본 프로젝트 - 복사본");

        Path master = originalRoot.resolve("app").resolve("master");
        Files.createDirectories(master);

        git(master, "init", "-b", "master");
        Files.writeString(master.resolve("Main.java"), "class Main {}\n");
        git(master, "add", ".");
        git(master, "commit", "-m", "init");

        // 브랜치명의 / 는 폴더명에서 %2F 가 된다(WorkspaceService.toBranchFolderName).
        git(master, "worktree", "add", "-b", "feature/login",
                originalRoot.resolve("app").resolve("feature%2Flogin").toString(), "master");

        copyTree(originalRoot, duplicateRoot);
    }

    @Test
    @DisplayName("복사만 하면 사본의 브랜치 폴더가 원본 저장소를 가리킨다(고치기 전 문제 재현)")
    void plainCopyPointsAtOriginal() throws Exception {
        String gitFile = Files.readString(duplicateRoot.resolve("app/feature%2Flogin/.git"));

        assertThat(normalize(gitFile)).contains(normalize(originalRoot.toString()) + "/");
    }

    @Test
    @DisplayName("재연결 후 사본의 worktree 양방향 연결이 모두 사본 안을 가리킨다")
    void relinkPointsBothDirectionsIntoDuplicate() throws Exception {
        GitWorktreeRelinker.Result result = GitWorktreeRelinker.relink(originalRoot, duplicateRoot);

        assertThat(result.rewritten()).isEqualTo(2);
        assertThat(result.external()).isZero();

        Path dupBranch = duplicateRoot.resolve("app/feature%2Flogin");
        Path dupMaster = duplicateRoot.resolve("app/master");

        String gitDir = git(dupBranch, "rev-parse", "--absolute-git-dir");
        assertThat(normalize(gitDir)).startsWith(normalize(dupMaster.resolve(".git").toString()));

        String list = git(dupMaster, "worktree", "list", "--porcelain");
        assertThat(normalize(list))
                .contains(normalize(dupBranch.toString()))
                // 사본 이름이 원본 이름으로 시작하므로 폴더 구분자까지 붙여 비교한다.
                .doesNotContain(normalize(originalRoot.toString()) + "/");
    }

    @Test
    @DisplayName("사본에서 브랜치 조회·전환·생성·커밋·병합을 해도 원본 폴더는 한 바이트도 바뀌지 않는다")
    void gitOperationsOnDuplicateDoNotTouchOriginal() throws Exception {
        Map<String, String> before = fingerprint(originalRoot);
        String originalFeatureHead = git(originalRoot.resolve("app/master"), "rev-parse", "feature/login");

        GitWorktreeRelinker.relink(originalRoot, duplicateRoot);

        Path dupMaster = duplicateRoot.resolve("app/master");
        Path dupBranch = duplicateRoot.resolve("app/feature%2Flogin");

        // 상태 확인 · 브랜치 조회
        assertThat(git(dupBranch, "status", "--porcelain")).isEmpty();
        assertThat(git(dupMaster, "branch", "--list")).contains("feature/login").contains("master");
        assertThat(git(dupBranch, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("feature/login");

        // 브랜치 폴더에서 커밋
        Files.writeString(dupBranch.resolve("Login.java"), "class Login {}\n");
        git(dupBranch, "add", ".");
        git(dupBranch, "commit", "-m", "login");

        // 새 브랜치(worktree) 생성 — ProjectService.createBranch 와 같은 방식
        git(dupMaster, "worktree", "add", "-b", "hotfix",
                duplicateRoot.resolve("app/hotfix").toString(), "master");

        // master 에서 병합, 그리고 전환
        git(dupMaster, "merge", "--no-edit", "feature/login");
        assertThat(Files.exists(dupMaster.resolve("Login.java"))).isTrue();
        git(dupMaster, "switch", "-c", "temp-switch");
        git(dupMaster, "switch", "master");

        // 원본: 파일·.git 내용이 그대로, 브랜치 HEAD 도 그대로
        assertThat(fingerprint(originalRoot)).isEqualTo(before);
        assertThat(git(originalRoot.resolve("app/master"), "rev-parse", "feature/login"))
                .isEqualTo(originalFeatureHead);
        assertThat(git(originalRoot.resolve("app/master"), "branch", "--list"))
                .doesNotContain("hotfix")
                .doesNotContain("temp-switch");
    }

    @Test
    @DisplayName("원본 폴더가 사라져도 사본의 브랜치 폴더는 동작한다")
    void duplicateSurvivesOriginalDeletion() throws Exception {
        GitWorktreeRelinker.relink(originalRoot, duplicateRoot);

        // 원본을 지우는 대신 이름을 바꿔 경로를 끊는다(같은 효과, 테스트 정리가 쉽다).
        Files.move(originalRoot, temp.resolve("moved-away"));

        Path dupBranch = duplicateRoot.resolve("app/feature%2Flogin");
        assertThat(git(dupBranch, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("feature/login");
    }

    @Test
    @DisplayName("원본과 사본 경로가 겹치면 아무것도 고치지 않고 멈춘다")
    void rejectsOverlappingRoots() {
        assertThatThrownBy(() -> GitWorktreeRelinker.relink(originalRoot, originalRoot.resolve("inner")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of(
                "git", "-c", "user.name=test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false"));
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();

        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 실패: " + out);
        }

        return out;
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').toLowerCase();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path dest = target.resolve(source.relativize(p).toString());

                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 폴더 안 모든 파일의 상대경로 → 내용 해시. */
    private static Map<String, String> fingerprint(Path root) throws Exception {
        Map<String, String> map = new TreeMap<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (Files.isRegularFile(p)) {
                    map.put(root.relativize(p).toString(),
                            HexFormat.of().formatHex(digest.digest(Files.readAllBytes(p))));
                }
            }
        }

        return map;
    }
}
