package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyntaxCheckerTest {

    private final SyntaxChecker checker = new SyntaxChecker();

    @Test
    @DisplayName("완성된 자바 파일은 통과한다")
    void acceptsCompleteJava() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """;

        assertThat(checker.isValid("src/Main.java", code)).isTrue();
    }

    @Test
    @DisplayName("괄호를 닫지 않은 파일은 통과하지 못한다")
    void rejectsUnclosedBrace() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hi");
                """;

        assertThat(checker.isValid("src/Main.java", code)).isFalse();
    }

    @Test
    @DisplayName("쓰다 만 문장은 통과하지 못한다")
    void rejectsHalfWrittenStatement() {
        String code = """
                public class Main {
                    void run() {
                        int total = ;
                    }
                }
                """;

        assertThat(checker.isValid("Main.java", code)).isFalse();
    }

    @Test
    @DisplayName("타입 오류는 문법 검사에서 잡지 않는다")
    void doesNotCheckTypes() {
        // 문법 검사는 "다 썼는가"만 본다. 없는 메서드 호출 같은 것은 실행할 때의
        // 컴파일이 잡고, 그때 작성자를 찾아 제외한다.
        String code = """
                public class Main {
                    void run() {
                        noSuchMethod();
                    }
                }
                """;

        assertThat(checker.isValid("Main.java", code)).isTrue();
    }

    @Test
    @DisplayName("자바 17 문법(record, 텍스트 블록)을 받아들인다")
    void acceptsJava17() {
        String code = "public record Point(int x, int y) { String s() { return \"\"\"\n  a\n  \"\"\"; } }";

        assertThat(checker.isValid("Point.java", code)).isTrue();
    }

    @Test
    @DisplayName("자바가 아닌 파일은 검사하지 않고 통과시킨다")
    void skipsOtherLanguages() {
        assertThat(checker.isValid("main.py", "def broken(:")).isTrue();
        assertThat(checker.isValid("index.js", "function (")).isTrue();
        assertThat(checker.isValid(null, "{")).isTrue();
    }
}
