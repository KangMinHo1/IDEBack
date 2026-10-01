// 경로: src/main/java/com/myide/backend/service/saferun/RunAssembler.java
package com.myide.backend.service.saferun;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 실행용 임시 폴더를 조립한다. "누구의 변경을 넣고 누구의 변경을 뺄지"를 파일 단위로 정한다.
 *
 * 규칙:
 * <ol>
 *   <li>바탕은 green(마지막으로 컴파일에 성공한 사본)이다.</li>
 *   <li>장부에 있는 파일은
 *     <ul>
 *       <li>작성자에 실행한 본인이 있으면 <b>언제나 live</b> — 다른 작성자를 제외해도 되돌리지
 *           않는다. 되돌리면 본인이 방금 고친 것이 말없이 빠진다.</li>
 *       <li>본인이 없으면, 작성자가 <b>전부</b> 포함 목록에 있을 때만 live, 아니면 green.
 *           같은 파일을 여럿이 고쳤어도 줄 단위로 가르지 않고 파일 통째로 정한다.</li>
 *     </ul>
 *   </li>
 *   <li>장부에 없는데 live 와 green 이 다른 파일(출처를 모르는 변경)은, 요청이 있을 때만 live 를
 *       쓴다. 장부는 메모리에만 있어 서버를 재시작하면 사라지고, 동시 편집을 거치지 않는
 *       변경(코드 생성 등)은 애초에 적히지 않는다. 이 규칙이 없으면 그런 변경이 실행에서
 *       조용히 빠진다. 다만 "green + 본인 변경만"으로 돌리는 마지막 시도에서는 쓰지 않는다.</li>
 *   <li>샌드박스면 그 위에 내 샌드박스 파일을 덮고, 내가 지운 파일을 지운다.</li>
 * </ol>
 *
 * live 폴더에는 절대 쓰지 않는다. 읽기만 한다.
 */
@Component
public class RunAssembler {

    /** 복사하지 않는 이름. 파일 트리가 숨기는 것(FileService.IGNORED_FILE_TREE_NAMES)과 같다. */
    private static final Set<String> IGNORED_NAMES = Set.of(
            ".git", "node_modules", ".next", "target", "build", "out", "dist", ".gradle", ".idea"
    );

    /**
     * @param green                 정상 버전 폴더. 아직 없으면 존재하지 않는 경로여도 된다
     * @param live                  팀이 함께 고치는 브랜치 폴더
     * @param runDir                만들 실행 폴더(비어 있거나 없어야 한다)
     * @param ledger                편집 장부 사본(파일경로 → 작성자 집합)
     * @param requester             실행한 본인
     * @param included              변경을 넣을 작성자
     * @param includeUnknownChanges 규칙 3 을 적용할지
     * @param sandboxFiles          내 샌드박스의 files/ 폴더. 샌드박스 실행이 아니면 null
     * @param sandboxDeleted        내 샌드박스에서 지운 파일 경로
     */
    public record Spec(
            Path green,
            Path live,
            Path runDir,
            Map<String, Set<Long>> ledger,
            Long requester,
            Set<Long> included,
            boolean includeUnknownChanges,
            Path sandboxFiles,
            Set<String> sandboxDeleted
    ) {}

    public void assemble(Spec spec) throws IOException {
        Files.createDirectories(spec.runDir());

        // 규칙 3 을 쓸 때는 live 를 바탕으로 깔고 "green 이어야 하는 파일"만 되돌린다.
        // 그러면 장부에 없는 변경이 저절로 live 로 들어간다. 쓰지 않을 때는 반대로
        // green 을 깔고 "live 여야 하는 파일"만 올린다. 결과는 규칙과 같고, 폴더 전체를
        // 파일마다 비교하지 않아도 된다.
        boolean baseIsLive = spec.includeUnknownChanges();
        Path base = baseIsLive ? spec.live() : spec.green();
        Path other = baseIsLive ? spec.green() : spec.live();

        copyTree(base, spec.runDir());

        // 부모 폴더를 먼저 처리해야 그 안의 파일에 내린 결정이 덮이지 않는다.
        List<String> paths = new ArrayList<>(spec.ledger().keySet());
        paths.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));

        for (String path : paths) {
            boolean wantLive = wantsLive(spec.ledger().get(path), spec.requester(), spec.included());

            if (wantLive != baseIsLive) {
                replace(other, spec.runDir(), path);
            }
        }

        if (spec.sandboxFiles() != null) {
            copyTree(spec.sandboxFiles(), spec.runDir());

            for (String deleted : spec.sandboxDeleted() == null ? Set.<String>of() : spec.sandboxDeleted()) {
                Path target = resolveInside(spec.runDir(), deleted);

                if (target != null) {
                    deleteRecursively(target);
                }
            }
        }
    }

    /** 규칙 2. 이 파일을 live 버전으로 넣을지. */
    static boolean wantsLive(Set<Long> authors, Long requester, Set<Long> included) {
        if (authors == null || authors.isEmpty()) {
            return true;
        }

        if (requester != null && authors.contains(requester)) {
            return true;
        }

        return included != null && included.containsAll(authors);
    }

    /** 실행 폴더의 한 경로를 source 쪽 것으로 갈아 끼운다. source 에 없으면 지운 것으로 본다. */
    private void replace(Path source, Path runDir, String relativePath) throws IOException {
        Path target = resolveInside(runDir, relativePath);
        Path from = resolveInside(source, relativePath);

        // 방 이름은 클라이언트가 만든다. "../" 가 섞인 경로로 폴더 밖을 건드리지 못하게 한다.
        if (target == null || from == null) {
            return;
        }

        deleteRecursively(target);

        if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            copyTree(from, target);
        } else if (Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            Files.copy(from, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** root 아래의 상대경로를 실제 경로로 바꾼다. root 밖을 가리키면 null. */
    public static Path resolveInside(Path root, String relativePath) {
        String normalized = EditLedger.normalizePath(relativePath);

        if (normalized.isEmpty()) {
            return null;
        }

        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(normalized).normalize();

        return resolved.startsWith(base) && !resolved.equals(base) ? resolved : null;
    }

    /** 폴더를 통째로 복사한다. 빌드 산출물과 .git 은 건너뛴다. source 가 없으면 아무것도 하지 않는다. */
    public static void copyTree(Path source, Path destination) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            return;
        }

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && isIgnored(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                Files.createDirectories(destination.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // .git 은 worktree 에서는 폴더가 아니라 파일이다. 심볼릭 링크도 따라가지 않는다.
                if (attrs.isRegularFile() && !isIgnored(file)) {
                    Files.copy(
                            file,
                            destination.resolve(source.relativize(file).toString()),
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isIgnored(Path path) {
        String name = path.getFileName().toString();
        return IGNORED_NAMES.contains(name) || name.endsWith(".class");
    }

    public static void deleteRecursively(Path target) throws IOException {
        if (target == null || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
