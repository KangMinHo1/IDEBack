package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컴파일러 출력에서 에러 파일을 장부와 같은 경로로 뽑는지 확인한다.
 *
 * 경로가 장부와 한 글자라도 다르면 작성자를 찾지 못해, 뺄 수 있었던 사람을 못 빼고
 * 곧장 "내 변경만"으로 떨어진다.
 */
class CompileErrorParserTest {

    @Test
    @DisplayName("javac 출력에서 에러 파일을 뽑는다")
    void parsesJavac() {
        String output = """
                Main.java:5: error: ';' expected
                        int x = 1
                                 ^
                Helper.java:12: error: cannot find symbol
                  symbol:   method nope()
                2 errors
                """;

        assertThat(CompileErrorParser.errorFiles(output, "")).containsExactly("Main.java", "Helper.java");
    }

    @Test
    @DisplayName("하위 폴더에서 컴파일했으면 그 폴더를 앞에 붙인다")
    void prefixesWorkingDirectory() {
        // 콘솔 실행은 실행할 파일이 있는 폴더로 들어가 javac *.java 를 돌린다.
        String output = "Main.java:3: error: class, interface, enum, or record expected\n";

        assertThat(CompileErrorParser.errorFiles(output, "src/app")).containsExactly("src/app/Main.java");
    }

    @Test
    @DisplayName("gradle 출력의 컨테이너 절대경로를 브랜치 폴더 기준으로 바꾼다")
    void parsesGradle() {
        String output = """
                > Task :compileJava FAILED
                /app/src/main/java/com/shop/OrderService.java:27: error: incompatible types: String cannot be converted to int
                        int total = name;
                                    ^
                /app/src/main/java/com/shop/web/OrderController.java:14: error: cannot find symbol
                1 warning
                2 errors

                FAILURE: Build failed with an exception.
                """;

        assertThat(CompileErrorParser.errorFiles(output, "")).containsExactly(
                "src/main/java/com/shop/OrderService.java",
                "src/main/java/com/shop/web/OrderController.java"
        );
    }

    @Test
    @DisplayName("같은 파일의 에러가 여러 개여도 한 번만 나온다")
    void deduplicates() {
        String output = """
                Main.java:5: error: ';' expected
                Main.java:9: error: ';' expected
                """;

        assertThat(CompileErrorParser.errorFiles(output, "")).containsExactly("Main.java");
    }

    @Test
    @DisplayName("경고는 세지 않는다")
    void ignoresWarnings() {
        String output = """
                Main.java:5: warning: [deprecation] old() in Legacy has been deprecated
                Note: Main.java uses unchecked or unsafe operations.
                """;

        assertThat(CompileErrorParser.errorFiles(output, "")).isEmpty();
    }

    @Test
    @DisplayName("윈도우식 줄바꿈과 경로 구분자도 받아들인다")
    void handlesWindowsStyle() {
        String output = "pkg\\Main.java:5: error: ';' expected\r\nOther.java:1: error: reached end of file\r\n";

        assertThat(CompileErrorParser.errorFiles(output, "")).containsExactly("pkg/Main.java", "Other.java");
    }

    @Test
    @DisplayName("프로젝트 밖의 파일은 무시한다")
    void ignoresFilesOutsideTheProject() {
        String output = "/usr/lib/jvm/src/java/lang/String.java:10: error: something\n";

        assertThat(CompileErrorParser.errorFiles(output, "")).isEmpty();
    }

    @Test
    @DisplayName("출력이 없으면 빈 결과다")
    void emptyOutput() {
        assertThat(CompileErrorParser.errorFiles(null, "")).isEmpty();
        assertThat(CompileErrorParser.errorFiles("  ", "")).isEmpty();
        assertThat(CompileErrorParser.errorFiles("BUILD SUCCESSFUL", "")).isEmpty();
    }
}
