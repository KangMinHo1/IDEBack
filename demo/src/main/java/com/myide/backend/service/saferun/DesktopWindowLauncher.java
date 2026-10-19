// 경로: src/main/java/com/myide/backend/service/saferun/DesktopWindowLauncher.java
package com.myide.backend.service.saferun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Spring Boot 프로젝트에서 서버와 따로 띄울 창(Swing·AWT) 클래스를 고른다.
 *
 * bootRun 은 @SpringBootApplication 의 main 하나만 돌린다. 창 클래스에 main 이 따로 있으면
 * 아무도 그것을 부르지 않아, 화면 중계는 켜졌는데 창이 없는 검은 화면만 뜬다. AI 가 만든 코드가
 * 대개 이런 모양이라 사용자 코드를 고치게 하지 않고 서버가 대신 띄운다.
 *
 * 창은 서버와 다른 JVM 으로 띄운다. 창이 서버 API 를 http://localhost 로 부르는, 실제 데스크톱
 * 클라이언트와 같은 구조다. 그래서 창 코드가 Spring 빈(@Autowired)을 직접 쓰면 동작하지 않고,
 * 창을 닫아도(EXIT_ON_CLOSE) 서버는 꺼지지 않는다.
 */
public final class DesktopWindowLauncher {

    /** Spring Boot 템플릿의 소스 위치. gradle 이 여기 것만 build/classes/java/main 에 컴파일한다. */
    private static final String SOURCE_DIR = "src/main/java";

    /** 라이브러리 목록을 못 받았을 때 쓰는 최소 경로. gradle compileJava 가 이미 만들어 둔 산출물이다. */
    public static final String FALLBACK_CLASSPATH = "build/classes/java/main:build/resources/main";

    private static final String CLASSPATH_MARKER = "IDE_CLASSPATH=";

    /**
     * gradle 에게 프로젝트의 실행 라이브러리 목록 전체를 묻는 init 스크립트.
     * 프로젝트 클래스만으로 띄우면 창에서 Jackson 처럼 build.gradle 에 적힌 라이브러리를 못 쓴다.
     * 사용자 build.gradle 을 고치지 않으려고 init 스크립트로 작업을 끼워 넣는다.
     */
    private static final String CLASSPATH_INIT_SCRIPT =
            "allprojects { plugins.withId(\"java\") { def cp = sourceSets.main.runtimeClasspath; "
                    + "tasks.register(\"ideRuntimeClasspath\") { doLast { println(\"" + CLASSPATH_MARKER
                    + "\" + cp.asPath) } } } }";

    private static final String CLASSPATH_INIT_FILE = "/tmp/ide-classpath.gradle";

    /** 서버가 응답하기를 기다리는 최대 시간(초). 포트를 바꾼 프로젝트도 창은 결국 뜨게 한다. */
    private static final int SERVER_WAIT_SECONDS = 120;

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MAIN = Pattern.compile("static\\s+void\\s+main\\s*\\(");
    private static final String SPRING_APP = "@SpringBootApplication";

    private record Source(String relativePath, String simpleName, String content) {}

    private DesktopWindowLauncher() {
    }

    /**
     * 띄울 창 클래스의 이름(패키지 포함). 없으면 빈 값.
     *
     * 여럿이면 하나만 띄운다. 지금 열어 둔 파일이 창 클래스면 그것을, 아니면 경로 순서로 첫 번째를.
     *
     * @param projectRoot 실행 폴더(브랜치 폴더와 같은 구조)
     * @param activeFile  사용자가 실행할 때 열어 둔 파일(브랜치 폴더 기준 상대경로). 없으면 null
     */
    public static Optional<String> findWindowClass(Path projectRoot, String activeFile) {
        if (projectRoot == null) {
            return Optional.empty();
        }

        List<Source> sources = readSources(projectRoot);
        List<Source> springApps = sources.stream()
                .filter(source -> source.content().contains(SPRING_APP))
                .toList();

        List<Source> windows = sources.stream()
                .filter(source -> !source.content().contains(SPRING_APP))
                .filter(source -> source.content().contains("javax.swing") || source.content().contains("java.awt"))
                .filter(source -> MAIN.matcher(source.content()).find())
                // 시작 클래스가 이미 창을 띄우고 있으면 또 띄우면 창이 두 개가 된다.
                .filter(source -> springApps.stream().noneMatch(app -> mentions(app, source.simpleName())))
                .sorted(Comparator.comparing(Source::relativePath))
                .toList();

        if (windows.isEmpty()) {
            return Optional.empty();
        }

        String active = activeFile == null ? "" : EditLedger.normalizePath(activeFile);

        Source chosen = windows.stream()
                .filter(source -> source.relativePath().equals(active))
                .findFirst()
                .orElse(windows.get(0));

        return Optional.of(qualifiedName(chosen));
    }

    /** 라이브러리 목록을 묻는 셸 명령. 서버를 켜기 전에 따로 돌린다(같은 프로젝트에 gradle 을 겹쳐 돌리지 않게). */
    public static String classpathShell() {
        return "printf '%s\\n' " + RunCommands.quote(CLASSPATH_INIT_SCRIPT) + " > " + CLASSPATH_INIT_FILE
                + " && gradle -q --init-script " + CLASSPATH_INIT_FILE + " ideRuntimeClasspath 2>&1";
    }

    /** classpathShell 출력에서 라이브러리 목록을 꺼낸다. 없으면 빈 값. */
    public static Optional<String> parseClasspath(String output) {
        if (output == null) {
            return Optional.empty();
        }

        return output.lines()
                .map(String::trim)
                .filter(line -> line.startsWith(CLASSPATH_MARKER))
                .map(line -> line.substring(CLASSPATH_MARKER.length()).trim())
                .filter(classpath -> !classpath.isEmpty())
                .findFirst();
    }

    /**
     * 서버 실행 명령 앞에 붙이는 창 실행 명령. 뒤에서(&) 돌아 서버 실행을 막지 않는다.
     *
     * 서버가 뜨기 전에 창이 먼저 뜨면, 시작하자마자 API 를 부르는 창은 연결 실패로 빈 채로 뜬다.
     * 그래서 서버 포트가 응답할 때까지 기다렸다가 띄운다. curl 은 -f 없이 쓰면 404·401 이어도
     * 성공으로 끝나므로 "서버가 받기 시작했다"만 본다.
     */
    public static String launchShell(String className, String classpath, int serverPort) {
        return "( i=0; while [ $i -lt " + SERVER_WAIT_SECONDS + " ] && "
                + "! curl -s -o /dev/null http://localhost:" + serverPort + "/; "
                + "do sleep 1; i=$((i+1)); done; "
                + "java -cp " + RunCommands.quote(classpath) + " " + RunCommands.quote(className) + " ) & ";
    }

    private static List<Source> readSources(Path projectRoot) {
        Path sourceDir = projectRoot.resolve(SOURCE_DIR);
        List<Source> sources = new ArrayList<>();

        if (!Files.isDirectory(sourceDir)) {
            return sources;
        }

        try (Stream<Path> files = Files.walk(sourceDir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String fileName = file.getFileName().toString();

                sources.add(new Source(
                        projectRoot.relativize(file).toString().replace('\\', '/'),
                        fileName.substring(0, fileName.length() - ".java".length()),
                        Files.readString(file, StandardCharsets.UTF_8)
                ));
            }
        } catch (IOException e) {
            // 창을 못 띄워도 서버 실행은 그대로 간다. 지금과 같은 검은 화면이 될 뿐이다.
            return List.of();
        }

        return sources;
    }

    private static boolean mentions(Source source, String simpleName) {
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(source.content()).find();
    }

    private static String qualifiedName(Source source) {
        Matcher matcher = PACKAGE.matcher(source.content());

        return matcher.find() ? matcher.group(1) + "." + source.simpleName() : source.simpleName();
    }
}
