package com.myide.backend.service.saferun;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunCommandsTest {

    @Test
    @DisplayName("Java 콘솔과 Spring Boot 에만 안전 실행을 적용한다")
    void supportedTemplates() {
        assertThat(RunCommands.supports(TemplateType.CONSOLE, LanguageType.JAVA)).isTrue();
        assertThat(RunCommands.supports(TemplateType.SPRING_BOOT, LanguageType.JAVA)).isTrue();

        assertThat(RunCommands.supports(TemplateType.CONSOLE, LanguageType.PYTHON)).isFalse();
        assertThat(RunCommands.supports(TemplateType.CONSOLE, LanguageType.CPP)).isFalse();
        assertThat(RunCommands.supports(TemplateType.REACT, LanguageType.JAVASCRIPT)).isFalse();
        assertThat(RunCommands.supports(TemplateType.NEXT, LanguageType.TYPESCRIPT)).isFalse();
    }

    @Test
    @DisplayName("Java 콘솔은 기존 실행 명령을 컴파일과 실행으로 나눈다")
    void javaConsole() {
        RunCommands.Commands commands =
                RunCommands.forTemplate(TemplateType.CONSOLE, LanguageType.JAVA, "Main.java");

        assertThat(commands.workingDir()).isEmpty();
        assertThat(commands.compile()).isEqualTo("javac -encoding UTF-8 *.java");
        assertThat(commands.run()).isEqualTo("java -cp . $(basename 'Main.java' .java)");
        assertThat(commands.runShell()).isEqualTo("java -cp . $(basename 'Main.java' .java)");
    }

    @Test
    @DisplayName("Java 콘솔은 컴파일 전에 예전 .class 를 지운다")
    void javaConsoleCleansStaleClasses() {
        // 컨테이너를 재사용한다. 앞선 시도에서 만든 .class 가 남아 있으면, 이번 조합에서
        // 뺀 파일을 참조하는 코드가 컴파일을 통과해 버린다.
        String shell = RunCommands.forTemplate(TemplateType.CONSOLE, LanguageType.JAVA, "Main.java")
                .compileShell();

        assertThat(shell).isEqualTo("{ find . -name '*.class' -delete; javac -encoding UTF-8 *.java ; } 2>&1");
    }

    @Test
    @DisplayName("하위 폴더의 파일은 그 폴더에서 컴파일하고 실행한다")
    void javaConsoleInSubfolder() {
        RunCommands.Commands commands =
                RunCommands.forTemplate(TemplateType.CONSOLE, LanguageType.JAVA, "week1/hello/Main.java");

        assertThat(commands.workingDir()).isEqualTo("week1/hello");
        assertThat(commands.compileShell()).startsWith("cd 'week1/hello' || exit 1; ");
        assertThat(commands.runShell()).startsWith("cd 'week1/hello' || exit 1; ");
    }

    @Test
    @DisplayName("Spring Boot 는 gradle 로 컴파일한 뒤 bootRun 한다")
    void springBoot() {
        RunCommands.Commands commands =
                RunCommands.forTemplate(TemplateType.SPRING_BOOT, LanguageType.JAVA, "src/main/java/App.java");

        assertThat(commands.workingDir()).isEmpty();

        // gradle 은 지워진 소스의 산출물을 스스로 정리한다. .class 를 지우면 증분 컴파일이 깨진다.
        assertThat(commands.compileShell()).isEqualTo("{ gradle compileJava ; } 2>&1");
        assertThat(commands.runShell()).isEqualTo("gradle bootRun");
    }

    @Test
    @DisplayName("경로에 든 따옴표가 셸 명령을 깨지 못한다")
    void quotesHostilePaths() {
        String shell = RunCommands
                .forTemplate(TemplateType.CONSOLE, LanguageType.JAVA, "a'; rm -rf /; '/Main.java")
                .compileShell();

        // 경로는 클라이언트가 보낸 값이다. 따옴표를 닫고 명령을 끼워 넣지 못해야 한다.
        assertThat(shell).startsWith("cd 'a'\\''; rm -rf /; '\\''' || exit 1; ");
    }
}
