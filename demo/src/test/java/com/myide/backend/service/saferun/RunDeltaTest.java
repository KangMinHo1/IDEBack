package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시도 사이에 달라진 파일만 골라내는지 확인한다.
 *
 * 덜 골라내면 컨테이너에 앞선 조합의 파일이 남아 "뺐다고 한 변경"이 실제로는 들어간
 * 채 컴파일된다. 더 골라내면 gradle 이 매번 전부 다시 컴파일해 재사용의 이득이 없다.
 */
class RunDeltaTest {

    @TempDir
    Path temp;

    private Path write(String root, String path, String content) throws IOException {
        Path file = temp.resolve(root).resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return temp.resolve(root);
    }

    @Test
    @DisplayName("바뀐 파일·새 파일·없어진 파일을 가려낸다")
    void detectsChanges() throws IOException {
        write("prev", "Same.java", "same");
        write("prev", "src/Changed.java", "v1");
        Path previous = write("prev", "Gone.java", "gone");

        write("next", "Same.java", "same");
        write("next", "src/Changed.java", "v2");
        Path next = write("next", "src/New.java", "new");

        RunDelta delta = RunDelta.between(previous, next);

        assertThat(delta.changed()).containsExactly("src/Changed.java", "src/New.java");
        assertThat(delta.removed()).containsExactly("Gone.java");
        assertThat(delta.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("같은 폴더끼리는 달라진 것이 없다")
    void identicalTrees() throws IOException {
        Path previous = write("prev", "a/A.java", "a");
        Path next = write("next", "a/A.java", "a");

        assertThat(RunDelta.between(previous, next).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("바뀐 파일만 같은 폴더 구조로 모은다")
    void copiesOnlyChangedFiles() throws IOException {
        write("prev", "Same.java", "same");
        Path previous = write("prev", "src/pkg/Changed.java", "v1");

        write("next", "Same.java", "same");
        Path next = write("next", "src/pkg/Changed.java", "v2");

        Path deltaDir = temp.resolve("delta");
        RunDelta.between(previous, next).copyChangedTo(next, deltaDir);

        assertThat(Files.readString(deltaDir.resolve("src/pkg/Changed.java"))).isEqualTo("v2");
        assertThat(Files.exists(deltaDir.resolve("Same.java"))).isFalse();
    }

    @Test
    @DisplayName("없어진 파일을 지우는 명령은 경로를 따옴표로 감싼다")
    void removeShellQuotesPaths() throws IOException {
        write("prev", "my file.java", "x");
        Path previous = write("prev", "it's.java", "x");
        Path next = Files.createDirectories(temp.resolve("next"));

        assertThat(RunDelta.between(previous, next).removeShell())
                .isEqualTo("rm -f -- 'it'\\''s.java' 'my file.java'");
    }

    @Test
    @DisplayName("지울 것이 없으면 명령도 없다")
    void noRemovalsNoCommand() throws IOException {
        Path previous = write("prev", "A.java", "a");
        Path next = write("next", "A.java", "b");

        assertThat(RunDelta.between(previous, next).removeShell()).isEmpty();
    }
}
