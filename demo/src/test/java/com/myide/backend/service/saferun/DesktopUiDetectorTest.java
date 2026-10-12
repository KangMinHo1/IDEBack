package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 창을 쓰는 프로젝트에서만 화면 중계를 켜는지 확인한다.
 *
 * 잘못 켜면 API 만 있는 프로젝트의 미리보기에 빈 화면이 뜨고, 잘못 끄면 Swing 창이 안 보인다.
 */
class DesktopUiDetectorTest {

    @TempDir
    Path root;

    private void write(String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("Swing 을 쓰는 소스가 있으면 창을 쓰는 프로젝트다")
    void detectsSwing() throws IOException {
        write("src/main/java/com/example/demo/DemoApplication.java", "class DemoApplication {}");
        write("src/main/java/com/example/demo/AppWindow.java", "import javax.swing.JFrame;\nclass AppWindow {}");

        assertThat(DesktopUiDetector.usesDesktopUi(root)).isTrue();
    }

    @Test
    @DisplayName("API 만 있는 프로젝트는 창을 쓰지 않는다")
    void ignoresApiOnlyProject() throws IOException {
        write("src/main/java/com/example/demo/DemoApplication.java",
                "import org.springframework.boot.SpringApplication;\nclass DemoApplication {}");
        write("README.md", "javax.swing 은 쓰지 않는다");

        assertThat(DesktopUiDetector.usesDesktopUi(root)).isFalse();
    }

    @Test
    @DisplayName("빌드 산출물 폴더는 보지 않는다")
    void skipsBuildOutput() throws IOException {
        write("build/generated/Old.java", "import javax.swing.JFrame;");
        write(".gradle/cache/Old.java", "import java.awt.Frame;");

        assertThat(DesktopUiDetector.usesDesktopUi(root)).isFalse();
    }

    @Test
    @DisplayName("폴더가 없으면 창을 쓰지 않는 것으로 본다")
    void missingFolder() {
        assertThat(DesktopUiDetector.usesDesktopUi(root.resolve("nope"))).isFalse();
        assertThat(DesktopUiDetector.usesDesktopUi(null)).isFalse();
    }
}
