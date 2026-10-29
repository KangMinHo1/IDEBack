package com.myide.backend.service.workspace;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 폴더째 복사한 워크스페이스의 git worktree 연결을 사본 쪽으로 다시 잇는다.
 *
 * 왜 필요한가
 *  워크스페이스는 {project}/master 가 진짜 저장소이고, 다른 브랜치 폴더는
 *  `git worktree add` 로 만든 연결 폴더다. 이 연결은 양쪽에 절대경로로 적힌다.
 *    - 브랜치 폴더의 `.git` (파일)       : "gitdir: <원본>/{project}/master/.git/worktrees/{id}"
 *    - master/.git/worktrees/{id}/gitdir : "<원본>/{project}/{branchFolder}/.git"
 *  폴더를 그대로 복사하면 사본의 브랜치 폴더가 여전히 원본 저장소의 worktree 정보
 *  (HEAD·index)를 쓰게 된다. 사본에서 브랜치를 바꾸거나 커밋하면 원본의 상태가
 *  바뀌고, 원본을 지우면 사본의 브랜치 폴더가 깨진다.
 *
 * 하는 일
 *  사본 안의 위 두 종류 파일만 찾아, 경로가 원본 루트 아래를 가리키면 같은 상대
 *  위치의 사본 경로로 바꿔 쓴다. 원본 폴더는 읽지도 쓰지도 않는다. 원본 루트 밖을
 *  가리키는 연결(사용자가 바깥에 만든 worktree)은 고칠 대상이 없으므로 경고만 남긴다.
 *
 * `git worktree repair` 를 쓰지 않는 이유: 사본의 `.git` 파일이 가리키는 원본 관리
 * 폴더가 멀쩡히 살아 있어서, repair 가 "고장 아님"으로 보고 지나칠 수 있다. 무엇을
 * 바꾸는지 정확히 아는 쪽이 안전하다.
 */
@Slf4j
public final class GitWorktreeRelinker {

    private static final String GITDIR_PREFIX = "gitdir:";

    /** 이 이름의 폴더 안에는 worktree 연결이 없다. 큰 폴더를 훑지 않으려고 건너뛴다. */
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", "build", "target", ".gradle", "dist");

    /** {root}/{project}/{branch}/.git 이 깊이 3. 여유를 하나 둔다. */
    private static final int MAX_DEPTH = 4;

    private GitWorktreeRelinker() {
    }

    /** @param rewritten 바꿔 쓴 파일 수   @param external 원본 루트 밖을 가리켜 그대로 둔 연결 수 */
    public record Result(int rewritten, int external) {}

    public static Result relink(Path originalRoot, Path duplicateRoot) throws IOException {
        Path from = originalRoot.toAbsolutePath().normalize();
        Path to = duplicateRoot.toAbsolutePath().normalize();

        if (from.equals(to) || to.startsWith(from) || from.startsWith(to)) {
            // 이런 배치면 "원본 아래"인지로 사본 파일을 가려낼 수 없다. 잘못 바꾸느니 멈춘다.
            throw new IllegalArgumentException("원본과 사본 경로가 겹칩니다: " + from + " / " + to);
        }

        int[] counts = new int[2];

        Files.walkFileTree(to, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();

                        if (".git".equals(name)) {
                            relinkAdminDir(dir, from, to, counts);
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        return SKIP_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!".git".equals(file.getFileName().toString())) {
                            return FileVisitResult.CONTINUE;
                        }

                        // 최대 깊이에 걸린 폴더도 visitFile 로 들어온다.
                        if (attrs.isDirectory()) {
                            relinkAdminDir(file, from, to, counts);
                        } else if (attrs.isRegularFile()) {
                            relinkGitFile(file, from, to, counts);
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        log.warn("⚠️ [프로젝트 복제] git 연결 확인 중 읽지 못한 경로: {} ({})", file, exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });

        return new Result(counts[0], counts[1]);
    }

    /** 브랜치 폴더의 `.git` 파일: "gitdir: <관리 폴더>" */
    private static void relinkGitFile(Path gitFile, Path from, Path to, int[] counts) throws IOException {
        String content = Files.readString(gitFile, StandardCharsets.UTF_8);
        String firstLine = content.lines().findFirst().orElse("").trim();

        if (!firstLine.startsWith(GITDIR_PREFIX)) {
            return;
        }

        String target = firstLine.substring(GITDIR_PREFIX.length()).trim();
        String replaced = remap(target, gitFile.getParent(), from, to, counts);

        if (replaced != null) {
            write(gitFile, to, GITDIR_PREFIX + " " + replaced + "\n");
        }
    }

    /** 진짜 저장소의 `.git/worktrees/{id}/gitdir` 파일들: "<브랜치 폴더>/.git" */
    private static void relinkAdminDir(Path gitDir, Path from, Path to, int[] counts) throws IOException {
        Path worktrees = gitDir.resolve("worktrees");

        if (!Files.isDirectory(worktrees)) {
            return;
        }

        try (Stream<Path> entries = Files.list(worktrees)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Path backLink = entry.resolve("gitdir");

                if (!Files.isRegularFile(backLink)) {
                    continue;
                }

                String target = Files.readString(backLink, StandardCharsets.UTF_8).trim();
                String replaced = remap(target, entry, from, to, counts);

                if (replaced != null) {
                    write(backLink, to, replaced + "\n");
                }
            }
        }
    }

    /**
     * 원본 루트 아래의 절대경로면 사본 경로(git 표기, 슬래시)로 바꿔 돌려준다.
     * 상대경로는 사본 안에서 그대로 맞으므로 null(바꿀 것 없음).
     */
    private static String remap(String raw, Path base, Path from, Path to, int[] counts) {
        if (raw.isEmpty()) {
            return null;
        }

        Path path;

        try {
            path = Paths.get(raw);
        } catch (InvalidPathException e) {
            log.warn("⚠️ [프로젝트 복제] 해석할 수 없는 git 경로라 그대로 둡니다: {}", raw);
            return null;
        }

        if (!path.isAbsolute()) {
            return null;
        }

        Path normalized = path.normalize();

        if (normalized.startsWith(to)) {
            return null;
        }

        if (!normalized.startsWith(from)) {
            counts[1]++;
            log.warn("⚠️ [프로젝트 복제] {} 의 git 연결이 원본 폴더 밖({})을 가리켜 그대로 둡니다.", base, raw);
            return null;
        }

        counts[0]++;
        return to.resolve(from.relativize(normalized)).toString().replace('\\', '/');
    }

    private static void write(Path file, Path duplicateRoot, String content) throws IOException {
        // 사본 바깥 파일은 절대 쓰지 않는다(원본 보호의 마지막 확인).
        if (!file.toAbsolutePath().normalize().startsWith(duplicateRoot)) {
            throw new IOException("사본 밖의 파일을 고치려 했습니다: " + file);
        }

        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
