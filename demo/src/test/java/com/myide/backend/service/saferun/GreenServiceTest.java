package com.myide.backend.service.saferun;

import com.myide.backend.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정상 버전을 갱신한 뒤 장부가 올바르게 줄어드는지 확인한다.
 *
 * 장부를 덜 지우면 이미 반영된 변경이 계속 "수정 중"으로 보이고, 더 지우면 빌드
 * 도중에 고친 변경의 주인을 잃는다.
 */
class GreenServiceTest {

    private static final BranchKey KEY = BranchKey.of("ws-1", "shop", "feature/login");

    @TempDir
    Path temp;

    private Path live;
    private Path runDir;
    private SafeRunPaths paths;
    private EditLedger ledger;
    private GreenService greenService;

    @BeforeEach
    void setUp() throws IOException {
        live = temp.resolve("live");
        runDir = temp.resolve("run");
        Files.createDirectories(live);
        Files.createDirectories(runDir);

        WorkspaceService workspaceService =
                Mockito.mock(WorkspaceService.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(live).when(workspaceService).getProjectPath("ws-1", "shop", "feature/login");

        paths = new SafeRunPaths(temp.resolve("safe-run").toString(), workspaceService);
        ledger = new EditLedger();
        greenService = new GreenService(paths, ledger);
    }

    private void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("실행 폴더가 새 정상 버전이 되고 시각이 남는다")
    void promotesRunDirectory() throws IOException {
        write(runDir, "src/Main.java", "v2");

        assertThat(greenService.exists(KEY)).isFalse();

        greenService.promote(KEY, runDir);

        assertThat(greenService.exists(KEY)).isTrue();
        assertThat(Files.readString(paths.green(KEY).resolve("src/Main.java"))).isEqualTo("v2");
        assertThat(Long.parseLong(Files.readString(paths.greenStamp(KEY)))).isPositive();
    }

    @Test
    @DisplayName("이전 정상 버전에만 있던 파일은 남지 않는다")
    void replacesPreviousGreenEntirely() throws IOException {
        write(runDir, "Old.java", "v1");
        greenService.promote(KEY, runDir);

        Files.delete(runDir.resolve("Old.java"));
        write(runDir, "New.java", "v2");
        greenService.promote(KEY, runDir);

        assertThat(Files.exists(paths.green(KEY).resolve("Old.java"))).isFalse();
        assertThat(Files.readString(paths.green(KEY).resolve("New.java"))).isEqualTo("v2");
    }

    @Test
    @DisplayName("live 가 새 정상 버전과 같은 파일만 장부에서 지운다")
    void prunesOnlyFilesThatNowMatch() throws IOException {
        write(live, "Same.java", "built");
        write(runDir, "Same.java", "built");

        // 빌드하는 동안 팀원이 또 고쳤다. live 가 실행 폴더와 달라져 있다.
        write(live, "Moved.java", "edited again during build");
        write(runDir, "Moved.java", "built");

        ledger.record(KEY, "Same.java", 2L);
        ledger.record(KEY, "Moved.java", 3L);

        greenService.promote(KEY, runDir);

        assertThat(ledger.snapshot(KEY)).containsOnlyKeys("Moved.java");
    }

    @Test
    @DisplayName("지운 파일도 양쪽에 다 없으면 장부에서 지운다")
    void prunesDeletionsThatMadeItIntoGreen() throws IOException {
        write(runDir, "Keep.java", "built");
        write(live, "Keep.java", "built");

        ledger.record(KEY, "Deleted.java", 2L);

        greenService.promote(KEY, runDir);

        assertThat(ledger.snapshot(KEY)).isEmpty();
    }

    @Test
    @DisplayName("빌드 도중 새로 생긴 파일은 장부에 남는다")
    void keepsFilesCreatedDuringBuild() throws IOException {
        write(runDir, "Keep.java", "built");
        write(live, "Keep.java", "built");
        write(live, "Fresh.java", "created during build");

        ledger.record(KEY, "Fresh.java", 3L);

        greenService.promote(KEY, runDir);

        assertThat(ledger.snapshot(KEY)).containsOnlyKeys("Fresh.java");
    }

    @Test
    @DisplayName("live 폴더는 건드리지 않는다")
    void neverTouchesLive() throws IOException {
        write(live, "A.java", "live");
        write(runDir, "A.java", "built");

        greenService.promote(KEY, runDir);

        assertThat(Files.readString(live.resolve("A.java"))).isEqualTo("live");
    }
}
