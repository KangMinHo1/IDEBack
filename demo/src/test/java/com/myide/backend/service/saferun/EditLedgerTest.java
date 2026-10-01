package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 편집 장부가 "누가 어느 파일을 고쳤는지"를 정확히 들고 있는지 확인한다.
 *
 * 여기가 틀리면 실행에서 엉뚱한 사람의 변경이 빠지거나, 빠져야 할 변경이 들어간다.
 */
class EditLedgerTest {

    private static final BranchKey DEVELOP = BranchKey.of("ws-1", "shop", "develop");

    private final EditLedger ledger = new EditLedger();

    @Test
    @DisplayName("같은 파일을 여러 명이 고치면 작성자가 모두 적힌다")
    void collectsEveryAuthorOfAFile() {
        ledger.record(DEVELOP, "src/Main.java", 1L);
        ledger.record(DEVELOP, "src/Main.java", 2L);

        assertThat(ledger.authorsOf(DEVELOP, "src/Main.java")).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("처음 적힐 때만 true 를 돌려준다")
    void reportsOnlyTheFirstRecord() {
        // 글자를 칠 때마다 불리므로, 부르는 쪽이 이 값으로 "새 작성자인가"를 가린다.
        assertThat(ledger.record(DEVELOP, "src/Main.java", 1L)).isTrue();
        assertThat(ledger.record(DEVELOP, "src/Main.java", 1L)).isFalse();
    }

    @Test
    @DisplayName("경로 표기가 달라도 같은 파일로 본다")
    void normalizesPaths() {
        ledger.record(DEVELOP, "src\\Main.java", 1L);
        ledger.record(DEVELOP, "/src//Main.java", 2L);
        ledger.record(DEVELOP, "./src/Main.java", 3L);

        assertThat(ledger.snapshot(DEVELOP)).containsOnlyKeys("src/Main.java");
        assertThat(ledger.authorsOf(DEVELOP, "src/Main.java")).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("브랜치가 다르면 장부도 따로다")
    void branchesAreIndependent() {
        ledger.record(DEVELOP, "Main.java", 1L);

        assertThat(ledger.snapshot(BranchKey.of("ws-1", "shop", "master"))).isEmpty();
        assertThat(ledger.snapshot(BranchKey.of("ws-1", "blog", "develop"))).isEmpty();
    }

    @Test
    @DisplayName("옛 방식 샌드박스 브랜치(focus-u…)와 슬래시가 든 브랜치도 평범한 브랜치로 다룬다")
    void legacySandboxBranchIsJustABranch() {
        BranchKey legacy = BranchKey.of("ws-1", "shop", "focus-u12-login");
        BranchKey feature = BranchKey.of("ws-1", "shop", "feature/login");

        ledger.record(legacy, "Main.java", 12L);
        ledger.record(feature, "Main.java", 7L);

        assertThat(ledger.authorsOf(legacy, "Main.java")).containsExactly(12L);
        assertThat(ledger.authorsOf(feature, "Main.java")).containsExactly(7L);
    }

    @Test
    @DisplayName("비어 있거나 옛 이름인 브랜치는 master 로 본다")
    void blankBranchMeansMaster() {
        ledger.record(BranchKey.of("ws-1", "shop", null), "Main.java", 1L);
        ledger.record(BranchKey.of("ws-1", "shop", "main-repo"), "Main.java", 2L);

        assertThat(ledger.authorsOf(BranchKey.of("ws-1", "shop", "master"), "Main.java"))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("회원번호나 경로가 없으면 적지 않는다")
    void ignoresIncompleteInput() {
        assertThat(ledger.record(DEVELOP, "Main.java", null)).isFalse();
        assertThat(ledger.record(DEVELOP, "  ", 1L)).isFalse();
        assertThat(ledger.record(null, "Main.java", 1L)).isFalse();

        assertThat(ledger.snapshot(DEVELOP)).isEmpty();
    }

    @Test
    @DisplayName("조건에 맞는 파일만 지운다")
    void removesOnlyMatchingFiles() {
        ledger.record(DEVELOP, "A.java", 1L);
        ledger.record(DEVELOP, "B.java", 2L);

        ledger.removeIf(DEVELOP, path -> path.equals("A.java"));

        assertThat(ledger.snapshot(DEVELOP)).containsOnlyKeys("B.java");
        assertThat(ledger.allAuthors(DEVELOP)).containsExactly(2L);
    }

    @Test
    @DisplayName("사본은 그 뒤의 편집에 영향받지 않는다")
    void snapshotIsDetached() {
        ledger.record(DEVELOP, "A.java", 1L);

        var snapshot = ledger.snapshot(DEVELOP);
        ledger.record(DEVELOP, "A.java", 2L);
        ledger.record(DEVELOP, "B.java", 2L);

        // 실행 폴더를 조립하는 도중에 편집이 들어와도 조립 기준이 흔들리면 안 된다.
        assertThat(snapshot).containsOnlyKeys("A.java");
        assertThat(snapshot.get("A.java")).containsExactly(1L);
    }

    @Test
    @DisplayName("여럿이 동시에 적어도 빠지는 작성자가 없다")
    void isSafeUnderConcurrency() throws Exception {
        int writers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(writers);

        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (long i = 0; i < writers; i++) {
                long userId = i;
                tasks.add(() -> ledger.record(DEVELOP, "src/Main.java", userId));
            }

            for (Future<Boolean> result : pool.invokeAll(tasks)) {
                assertThat(result.get()).isTrue();
            }

            Set<Long> authors = ledger.authorsOf(DEVELOP, "src/Main.java");
            assertThat(authors).hasSize(writers);
        } finally {
            pool.shutdownNow();
        }
    }
}
