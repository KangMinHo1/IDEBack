package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot 프로젝트에서 따로 띄울 창 클래스를 고르는지 확인한다.
 *
 * bootRun 은 @SpringBootApplication 의 main 만 돌리므로, 창 클래스의 main 을 아무도 부르지 않으면
 * 미리보기에 빈 검은 화면만 뜬다.
 */
class DesktopWindowLauncherTest {

    private static final String SPRING_APP = """
            package com.example.demo;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            @SpringBootApplication
            public class DemoApplication {
                public static void main(String[] args) { }
            }
            """;

    @TempDir
    Path root;

    private void write(String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String window(String className) {
        return """
                package com.example.demo;
                import javax.swing.*;
                public class %s extends JFrame {
                    public static void main(String[] args) { }
                }
                """.formatted(className);
    }

    @Test
    @DisplayName("main 이 있는 Swing 클래스를 패키지 이름까지 붙여 고른다")
    void findsWindowClass() throws IOException {
        write("src/main/java/com/example/demo/DemoApplication.java", SPRING_APP);
        write("src/main/java/com/example/demo/StudentGradeGUI.java", window("StudentGradeGUI"));

        assertThat(DesktopWindowLauncher.findWindowClass(root, null))
                .contains("com.example.demo.StudentGradeGUI");
    }

    @Test
    @DisplayName("main 이 없는 Swing 클래스는 띄우지 않는다")
    void ignoresWindowWithoutMain() throws IOException {
        write("src/main/java/com/example/demo/DemoApplication.java", SPRING_APP);
        write("src/main/java/com/example/demo/Panel.java",
                "package com.example.demo;\nimport javax.swing.*;\nclass Panel extends JPanel {}");

        assertThat(DesktopWindowLauncher.findWindowClass(root, null)).isEmpty();
    }

    @Test
    @DisplayName("시작 클래스가 이미 창을 띄우면 또 띄우지 않는다")
    void skipsWhenSpringAppOpensWindow() throws IOException {
        write("src/main/java/com/example/demo/DemoApplication.java", """
                package com.example.demo;
                @SpringBootApplication
                public class DemoApplication {
                    public static void main(String[] args) {
                        SwingUtilities.invokeLater(() -> new StudentGradeGUI().setVisible(true));
                    }
                }
                """);
        write("src/main/java/com/example/demo/StudentGradeGUI.java", window("StudentGradeGUI"));

        assertThat(DesktopWindowLauncher.findWindowClass(root, null)).isEmpty();
    }

    @Test
    @DisplayName("시작 클래스 자신이 Swing 을 써도 시작 클래스는 따로 띄우지 않는다")
    void neverLaunchesSpringApp() throws IOException {
        write("src/main/java/com/example/demo/DemoApplication.java",
                SPRING_APP.replace("import org", "import javax.swing.*;\nimport org"));

        assertThat(DesktopWindowLauncher.findWindowClass(root, null)).isEmpty();
    }

    @Test
    @DisplayName("창 클래스가 여럿이면 지금 열어 둔 파일을 고른다")
    void prefersActiveFile() throws IOException {
        write("src/main/java/com/example/demo/DemoApplication.java", SPRING_APP);
        write("src/main/java/com/example/demo/AWindow.java", window("AWindow"));
        write("src/main/java/com/example/demo/BWindow.java", window("BWindow"));

        assertThat(DesktopWindowLauncher.findWindowClass(root, "src/main/java/com/example/demo/BWindow.java"))
                .contains("com.example.demo.BWindow");
    }

    @Test
    @DisplayName("열어 둔 파일이 창 클래스가 아니면 경로 순서로 첫 번째를 고른다")
    void fallsBackToFirstByPath() throws IOException {
        write("src/main/java/com/example/demo/DemoApplication.java", SPRING_APP);
        write("src/main/java/com/example/demo/BWindow.java", window("BWindow"));
        write("src/main/java/com/example/demo/AWindow.java", window("AWindow"));

        assertThat(DesktopWindowLauncher.findWindowClass(root, "src/main/java/com/example/demo/DemoApplication.java"))
                .contains("com.example.demo.AWindow");
    }

    @Test
    @DisplayName("빌드 산출물 폴더와 테스트 소스는 보지 않는다")
    void skipsBuildOutputAndTests() throws IOException {
        write("src/main/java/com/example/demo/DemoApplication.java", SPRING_APP);
        write("build/generated/com/example/demo/Old.java", window("Old"));
        write("src/test/java/com/example/demo/TestWindow.java", window("TestWindow"));

        assertThat(DesktopWindowLauncher.findWindowClass(root, null)).isEmpty();
    }

    @Test
    @DisplayName("패키지가 없는 클래스는 이름만 쓴다")
    void defaultPackage() throws IOException {
        write("src/main/java/Plain.java", window("Plain").replace("package com.example.demo;\n", ""));

        assertThat(DesktopWindowLauncher.findWindowClass(root, null)).contains("Plain");
    }

    @Test
    @DisplayName("창은 뒤에서, 서버가 응답한 뒤에 받은 라이브러리 목록으로 띄운다")
    void launchShell() {
        String shell = DesktopWindowLauncher.launchShell(
                "com.example.demo.StudentGradeGUI", "/app/build/classes/java/main:/root/.gradle/jackson.jar", 8080);

        assertThat(shell)
                .startsWith("( ")
                .endsWith(" ) & ")
                .contains("curl -s -o /dev/null http://localhost:8080/")
                .contains("[ $i -lt 120 ]")
                .contains("java -cp '/app/build/classes/java/main:/root/.gradle/jackson.jar' "
                        + "'com.example.demo.StudentGradeGUI'");

        // 서버를 기다린 다음에 창을 띄운다.
        assertThat(shell.indexOf("curl")).isLessThan(shell.indexOf("java -cp"));
    }

    @Test
    @DisplayName("gradle 출력에서 라이브러리 목록 줄만 골라낸다")
    void parsesClasspath() {
        String output = """
                Starting a Gradle Daemon
                IDE_CLASSPATH=/app/build/classes/java/main:/root/.gradle/jackson.jar
                """;

        assertThat(DesktopWindowLauncher.parseClasspath(output))
                .contains("/app/build/classes/java/main:/root/.gradle/jackson.jar");
    }

    @Test
    @DisplayName("라이브러리 목록을 못 받으면 빈 값이다")
    void missingClasspath() {
        assertThat(DesktopWindowLauncher.parseClasspath("FAILURE: Build failed")).isEmpty();
        assertThat(DesktopWindowLauncher.parseClasspath("IDE_CLASSPATH=   ")).isEmpty();
        assertThat(DesktopWindowLauncher.parseClasspath(null)).isEmpty();
    }
}
