package com.myide.backend.service.saferun;

import com.myide.backend.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeRunPathsTest {

    @TempDir
    Path root;

    private SafeRunPaths paths;

    @BeforeEach
    void setUp() {
        // 브랜치 폴더 이름 규칙은 WorkspaceService 것을 그대로 쓴다(진짜 메서드 호출).
        WorkspaceService workspaceService =
                Mockito.mock(WorkspaceService.class, Mockito.CALLS_REAL_METHODS);

        paths = new SafeRunPaths(root.toString(), workspaceService);
    }

    @Test
    @DisplayName("green·sandbox 는 워크스페이스·프로젝트·브랜치별로 갈린다")
    void layout() {
        BranchKey key = BranchKey.of("ws-1", "shop", "develop");

        assertThat(paths.green(key)).isEqualTo(root.resolve("ws-1/green/shop/develop"));
        assertThat(paths.greenStamp(key)).isEqualTo(root.resolve("ws-1/green/shop/develop.stamp"));
        assertThat(paths.sandbox(key, 7L)).isEqualTo(root.resolve("ws-1/sandbox/shop/develop/7"));
        assertThat(paths.run("abc")).isEqualTo(root.resolve("runs/abc"));
    }

    @Test
    @DisplayName("슬래시가 든 브랜치는 live 폴더와 같은 방식으로 한 폴더가 된다")
    void encodesSlashInBranchName() {
        BranchKey key = BranchKey.of("ws-1", "shop", "feature/login");

        assertThat(paths.green(key)).isEqualTo(root.resolve("ws-1/green/shop/feature%2Flogin"));
    }

    @Test
    @DisplayName("옛 방식 샌드박스 브랜치(focus-u…)도 평범한 브랜치처럼 자기 폴더를 갖는다")
    void legacySandboxBranch() {
        BranchKey key = BranchKey.of("ws-1", "shop", "focus-u12-login");

        assertThat(paths.green(key)).isEqualTo(root.resolve("ws-1/green/shop/focus-u12-login"));
        assertThat(paths.sandbox(key, 12L)).isEqualTo(root.resolve("ws-1/sandbox/shop/focus-u12-login/12"));
    }

    @Test
    @DisplayName("이름으로 루트 밖을 가리킬 수 없다")
    void refusesEscapingNames() {
        assertThatThrownBy(() -> paths.green(BranchKey.of("..", "..", "master")))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> paths.run("../../etc"))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> SafeRunPaths.workspaceRoot(root.toString(), ".."))
                .isInstanceOf(SecurityException.class);
    }
}
