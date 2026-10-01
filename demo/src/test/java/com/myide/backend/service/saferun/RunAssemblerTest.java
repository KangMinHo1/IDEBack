package com.myide.backend.service.saferun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실행 폴더 조립 규칙을 확인한다.
 *
 * 여기가 틀리면 "돌아가는 조합"이 아니라 엉뚱한 조합이 실행된다 — 빼야 할 변경이
 * 들어가 컴파일이 깨지거나, 본인이 방금 고친 것이 말없이 빠진다.
 *
 * 등장인물: 나(ME, 실행한 사람), 민수(MINSU), 지영(JIYOUNG).
 */
class RunAssemblerTest {

    private static final Long ME = 1L;
    private static final Long MINSU = 2L;
    private static final Long JIYOUNG = 3L;

    @TempDir
    Path temp;

    private Path green;
    private Path live;
    private Path runDir;
    private Map<String, Set<Long>> ledger;

    private final RunAssembler assembler = new RunAssembler();

    @BeforeEach
    void setUp() throws IOException {
        green = temp.resolve("green");
        live = temp.resolve("live");
        runDir = temp.resolve("runs/r1");
        ledger = new HashMap<>();

        Files.createDirectories(green);
        Files.createDirectories(live);
    }

    private void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** 실행 폴더의 내용(상대경로 → 내용). */
    private Map<String, String> assembled() throws IOException {
        Map<String, String> files = new TreeMap<>();

        try (Stream<Path> walk = Files.walk(runDir)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                files.put(runDir.relativize(path).toString().replace("\\", "/"), Files.readString(path));
            }
        }

        return files;
    }

    private void assemble(Set<Long> included, boolean includeUnknownChanges) throws IOException {
        assembler.assemble(new RunAssembler.Spec(
                green, live, runDir, ledger, ME, included, includeUnknownChanges, null, null));
    }

    @Test
    @DisplayName("포함된 작성자의 파일은 live, 제외된 작성자의 파일은 green 이다")
    void picksSidePerAuthor() throws IOException {
        write(green, "A.java", "green-A");
        write(green, "B.java", "green-B");
        write(live, "A.java", "live-A");
        write(live, "B.java", "live-B");
        ledger.put("A.java", Set.of(MINSU));
        ledger.put("B.java", Set.of(JIYOUNG));

        assemble(Set.of(ME, MINSU), true);

        assertThat(assembled())
                .containsEntry("A.java", "live-A")
                .containsEntry("B.java", "green-B");
    }

    @Test
    @DisplayName("여럿이 고친 파일은 작성자가 전부 포함돼야 live 다")
    void sharedFileNeedsEveryAuthor() throws IOException {
        write(green, "A.java", "green-A");
        write(live, "A.java", "live-A");
        ledger.put("A.java", Set.of(MINSU, JIYOUNG));

        // 줄 단위로 가르지 않는다. 한 명이라도 빠지면 파일 통째로 green.
        assemble(Set.of(ME, MINSU), true);
        assertThat(assembled()).containsEntry("A.java", "green-A");
    }

    @Test
    @DisplayName("여럿이 고친 파일도 작성자가 전부 포함되면 live 다")
    void sharedFileWithEveryAuthorIncluded() throws IOException {
        write(green, "A.java", "green-A");
        write(live, "A.java", "live-A");
        ledger.put("A.java", Set.of(MINSU, JIYOUNG));

        assemble(Set.of(ME, MINSU, JIYOUNG), true);

        assertThat(assembled()).containsEntry("A.java", "live-A");
    }

    @Test
    @DisplayName("내가 함께 고친 파일은 다른 작성자를 제외해도 live 로 남는다")
    void myFileIsNeverReverted() throws IOException {
        write(green, "A.java", "green-A");
        write(live, "A.java", "live-A (나와 지영이 함께 고침)");
        ledger.put("A.java", Set.of(ME, JIYOUNG));

        // 지영을 제외했다. 그래도 내 변경이 몰래 빠지면 안 된다.
        assemble(Set.of(ME), true);
        assertThat(assembled()).containsEntry("A.java", "live-A (나와 지영이 함께 고침)");
    }

    @Test
    @DisplayName("green + 본인 변경만 실행할 때도 내가 고친 파일은 live 다")
    void ownOnlyKeepsMyFiles() throws IOException {
        write(green, "Mine.java", "green-mine");
        write(green, "Shared.java", "green-shared");
        write(green, "Theirs.java", "green-theirs");
        write(live, "Mine.java", "live-mine");
        write(live, "Shared.java", "live-shared");
        write(live, "Theirs.java", "live-theirs");
        ledger.put("Mine.java", Set.of(ME));
        ledger.put("Shared.java", Set.of(ME, MINSU));
        ledger.put("Theirs.java", Set.of(MINSU));

        assemble(Set.of(ME), false);

        assertThat(assembled())
                .containsEntry("Mine.java", "live-mine")
                .containsEntry("Shared.java", "live-shared")
                .containsEntry("Theirs.java", "green-theirs");
    }

    @Test
    @DisplayName("장부에 없는 변경(출처 모름)은 요청했을 때 live 를 쓴다")
    void unknownChangesFollowLiveWhenAsked() throws IOException {
        write(green, "Old.java", "green-old");
        write(green, "Gone.java", "green-gone");
        write(live, "Old.java", "live-old (장부에 없음)");
        write(live, "New.java", "live-new (장부에 없음)");

        // 서버를 재시작해 장부가 비었거나, 동시 편집을 거치지 않은 변경이다.
        assemble(Set.of(ME), true);

        assertThat(assembled())
                .containsEntry("Old.java", "live-old (장부에 없음)")
                .containsEntry("New.java", "live-new (장부에 없음)")
                .doesNotContainKey("Gone.java");
    }

    @Test
    @DisplayName("green + 본인 변경만 실행할 때는 장부에 없는 변경을 넣지 않는다")
    void ownOnlyIgnoresUnknownChanges() throws IOException {
        write(green, "Old.java", "green-old");
        write(green, "Gone.java", "green-gone");
        write(live, "Old.java", "live-old (장부에 없음)");
        write(live, "New.java", "live-new (장부에 없음)");

        assemble(Set.of(ME), false);

        assertThat(assembled())
                .containsEntry("Old.java", "green-old")
                .containsEntry("Gone.java", "green-gone")
                .doesNotContainKey("New.java");
    }

    @Test
    @DisplayName("포함된 작성자가 지운 파일은 실행 폴더에서도 사라진다")
    void includedDeletionIsApplied() throws IOException {
        write(green, "Dead.java", "green-dead");
        ledger.put("Dead.java", Set.of(MINSU));

        assemble(Set.of(ME, MINSU), false);

        assertThat(assembled()).doesNotContainKey("Dead.java");
    }

    @Test
    @DisplayName("제외된 작성자가 지운 파일은 green 것이 되살아난다")
    void excludedDeletionIsUndone() throws IOException {
        write(green, "Dead.java", "green-dead");
        ledger.put("Dead.java", Set.of(JIYOUNG));

        assemble(Set.of(ME), true);

        assertThat(assembled()).containsEntry("Dead.java", "green-dead");
    }

    @Test
    @DisplayName("제외된 작성자가 새로 만든 파일은 실행 폴더에 들어가지 않는다")
    void excludedNewFileIsLeftOut() throws IOException {
        write(live, "Brand.java", "live-brand");
        ledger.put("Brand.java", Set.of(JIYOUNG));

        assemble(Set.of(ME), true);

        assertThat(assembled()).doesNotContainKey("Brand.java");
    }

    @Test
    @DisplayName("폴더 단위 변경(폴더 생성·삭제)도 같은 규칙을 따른다")
    void handlesDirectories() throws IOException {
        write(green, "old/pkg/A.java", "green-A");
        write(live, "fresh/pkg/B.java", "live-B");
        ledger.put("old", Set.of(MINSU));
        ledger.put("fresh", Set.of(MINSU));

        assemble(Set.of(ME, MINSU), false);

        assertThat(assembled())
                .containsEntry("fresh/pkg/B.java", "live-B")
                .doesNotContainKey("old/pkg/A.java");
    }

    @Test
    @DisplayName("샌드박스 파일은 맨 위에 덮이고, 샌드박스에서 지운 파일은 사라진다")
    void sandboxOverlay() throws IOException {
        write(green, "A.java", "green-A");
        write(green, "B.java", "green-B");
        write(live, "A.java", "live-A");
        write(live, "B.java", "live-B");

        Path sandboxFiles = temp.resolve("sandbox/files");
        write(sandboxFiles, "A.java", "sandbox-A");
        write(sandboxFiles, "Only.java", "sandbox-only");

        assembler.assemble(new RunAssembler.Spec(
                green, live, runDir, ledger, ME, Set.of(ME), true, sandboxFiles, Set.of("B.java")));

        assertThat(assembled())
                .containsEntry("A.java", "sandbox-A")
                .containsEntry("Only.java", "sandbox-only")
                .doesNotContainKey("B.java");
    }

    @Test
    @DisplayName("빌드 산출물과 .git 은 복사하지 않는다")
    void skipsBuildOutputAndGit() throws IOException {
        write(live, "Main.java", "live-main");
        write(live, "Main.class", "bytecode");
        write(live, "build/classes/A.class", "bytecode");
        write(live, "node_modules/x/index.js", "dep");
        // worktree 에서는 .git 이 폴더가 아니라 파일이다.
        write(live, ".git", "gitdir: ../master/.git/worktrees/develop");

        assemble(Set.of(ME), true);

        assertThat(assembled()).containsOnlyKeys("Main.java");
    }

    @Test
    @DisplayName("live 와 green 은 조립해도 그대로다")
    void neverTouchesLiveOrGreen() throws IOException {
        write(green, "A.java", "green-A");
        write(live, "A.java", "live-A");
        write(live, "B.java", "live-B");
        ledger.put("A.java", Set.of(JIYOUNG));
        ledger.put("B.java", Set.of(JIYOUNG));

        assemble(Set.of(ME), true);

        assertThat(Files.readString(live.resolve("A.java"))).isEqualTo("live-A");
        assertThat(Files.readString(live.resolve("B.java"))).isEqualTo("live-B");
        assertThat(Files.readString(green.resolve("A.java"))).isEqualTo("green-A");
        assertThat(Files.exists(green.resolve("B.java"))).isFalse();
    }

    @Test
    @DisplayName("폴더 밖을 가리키는 장부 경로는 무시한다")
    void ignoresEscapingLedgerPaths() throws IOException {
        Path outside = temp.resolve("runs/secret.txt");
        write(temp, "runs/secret.txt", "건드리면 안 됨");
        write(live, "A.java", "live-A");

        // 방 이름은 클라이언트가 만든다. "../" 로 실행 폴더 밖을 지우게 두면 안 된다.
        ledger.put("../secret.txt", Set.of(JIYOUNG));

        assemble(Set.of(ME), true);

        assertThat(Files.readString(outside)).isEqualTo("건드리면 안 됨");
        assertThat(assembled()).containsOnlyKeys("A.java");
    }

    @Test
    @DisplayName("green 이 아직 없어도 live 그대로 조립된다")
    void worksWithoutGreen() throws IOException {
        write(live, "A.java", "live-A");
        ledger.put("A.java", Set.of(MINSU));

        assembler.assemble(new RunAssembler.Spec(
                temp.resolve("no-green-yet"), live, runDir, ledger, ME, Set.of(ME, MINSU), true, null, null));

        assertThat(assembled()).containsEntry("A.java", "live-A");
    }
}
