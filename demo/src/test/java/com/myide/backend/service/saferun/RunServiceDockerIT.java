package com.myide.backend.service.saferun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.myide.backend.config.DockerConfig;
import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType;
import com.myide.backend.handler.CollaborationWebSocketHandler;
import com.myide.backend.handler.WorkspaceEventWebSocketHandler;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.service.DockerService;
import com.myide.backend.service.FileService;
import com.myide.backend.service.WorkspaceService;
import com.myide.backend.service.sandbox.SandboxService;
import com.myide.backend.service.sandbox.ThreeWayMerger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 Docker 로 안전 실행 흐름 전체를 돌려 본다.
 *
 * 평소 테스트에서는 돌지 않는다. Docker 와 ide-execution-env 이미지가 있어야 하고
 * 컨테이너를 띄우느라 느리기 때문이다(Spring Boot 는 의존성도 내려받는다). 직접 확인할
 * 때만 켠다.
 *
 * <pre>
 *   (PowerShell)  $env:SAFE_RUN_DOCKER_IT = "1"; .\gradlew.bat test --tests "*RunServiceDockerIT"
 *   (bash)        SAFE_RUN_DOCKER_IT=1 ./gradlew test --tests "*RunServiceDockerIT"
 * </pre>
 *
 * 등장인물: 나(ME, 실행한 사람), 지영(JIYOUNG).
 */
@EnabledIfEnvironmentVariable(named = "SAFE_RUN_DOCKER_IT", matches = "1")
class RunServiceDockerIT {

    private static final Long ME = 1L;
    private static final Long JIYOUNG = 3L;
    private static final BranchKey KEY = BranchKey.of("ws-1", "shop", "develop");

    private static final String MAIN = "public class Main { public static void main(String[] a) { Helper.go(); } }";
    private static final String HELPER_V1 = "public class Helper { static void go() { System.out.println(\"helper-v1\"); } }";
    private static final String HELPER_V2 = "public class Helper { static void go() { System.out.println(\"helper-v2\"); } }";
    private static final String HELPER_BROKEN = "public class Helper { static void go() { int x = 1 } }";

    @TempDir
    Path temp;

    private Path live;
    private SafeRunPaths paths;
    private EditLedger ledger;
    private AuthorStatusService authorStatusService;
    private GreenService greenService;
    private SandboxService sandboxService;
    private DockerClient dockerClient;
    private DockerService dockerService;
    private RunService runService;
    private long containersBefore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        live = temp.resolve("live");
        Files.createDirectories(live);

        WorkspaceService workspaceService =
                Mockito.mock(WorkspaceService.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(live).when(workspaceService).getProjectPath("ws-1", "shop", "develop");

        paths = new SafeRunPaths(temp.resolve("safe-run").toString(), workspaceService);
        ledger = new EditLedger();
        authorStatusService = new AuthorStatusService(ledger, new SyntaxChecker(), key -> {});
        greenService = new GreenService(paths, ledger);

        sandboxService = new SandboxService(
                paths,
                new ThreeWayMerger(),
                Mockito.mock(FileService.class),
                new CollaborationWebSocketHandler(),
                Mockito.mock(WorkspaceEventWebSocketHandler.class),
                authorStatusService,
                objectMapper
        );

        dockerClient = new DockerConfig().dockerClient();
        dockerService = new DockerService(dockerClient, workspaceService);

        runService = new RunService(
                dockerService,
                paths,
                ledger,
                authorStatusService,
                new RunAssembler(),
                greenService,
                Mockito.mock(SafeRunBroadcaster.class),
                sandboxService,
                Mockito.mock(UserRepository.class),
                objectMapper
        );

        ReflectionTestUtils.setField(runService, "consoleMaxBuilds", 3);
        ReflectionTestUtils.setField(runService, "springBootMaxBuilds", 3);
        ReflectionTestUtils.setField(runService, "buildTimeoutSeconds", 300L);
        ReflectionTestUtils.setField(runService, "springBootConcurrency", 1);
        runService.init();

        containersBefore = workbenchContainers().size();
    }

    /** 어떤 경우에도 컨테이너와 임시 폴더가 남으면 안 된다. */
    @AfterEach
    void nothingIsLeftBehind() throws Exception {
        // 컨테이너 강제 삭제는 요청 직후 바로 끝나지 않을 수 있어 잠깐 기다려 준다.
        for (int i = 0; i < 50 && workbenchContainers().size() > containersBefore; i++) {
            Thread.sleep(200);
        }

        assertThat(workbenchContainers()).as("안전 실행 컨테이너가 남아 있다").hasSize((int) containersBefore);
        assertThat(leftoverRunDirs()).as("실행용 임시 폴더가 남아 있다").isZero();
    }

    private List<String> workbenchContainers() {
        return dockerClient.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> "ide-execution-env".equals(container.getImage()))
                .filter(container -> container.getCommand() != null && container.getCommand().contains("sleep infinity"))
                .map(container -> container.getId())
                .toList();
    }

    private long leftoverRunDirs() throws IOException {
        if (!Files.isDirectory(paths.runsRoot())) {
            return 0;
        }

        try (Stream<Path> children = Files.list(paths.runsRoot())) {
            return children.count();
        }
    }

    /** 실행 한 번. 브라우저의 /ws/run 소켓 하나에 해당한다. */
    private final class Run {
        private final WebSocketSession session = Mockito.mock(WebSocketSession.class);
        private final String sessionId = "it-" + UUID.randomUUID();
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final long startedAt = System.currentTimeMillis();

        private Run(TemplateType templateType, String filePath, boolean sandbox) throws IOException {
            Mockito.when(session.getId()).thenReturn(sessionId);
            Mockito.when(session.isOpen()).thenAnswer(call -> closed.getCount() > 0);
            Mockito.doAnswer(call -> {
                messages.add(((TextMessage) call.getArgument(0)).getPayload());
                return null;
            }).when(session).sendMessage(Mockito.any());
            Mockito.doAnswer(call -> {
                closed.countDown();
                return null;
            }).when(session).close();

            runService.start(session, new RunService.Request(
                    KEY, filePath, LanguageType.JAVA, templateType, ME, Set.of(), Set.of(), sandbox, true));
        }

        /** 출력 창에 찍힐 글자(기계용 안내 줄은 프론트가 가로채므로 뺀다). */
        String shown() {
            StringBuilder shown = new StringBuilder();
            messages.stream().filter(message -> !message.startsWith(RunService.INFO_PREFIX)).forEach(shown::append);
            return shown.toString();
        }

        JsonNode info() throws IOException {
            for (String message : messages) {
                if (message.startsWith(RunService.INFO_PREFIX)) {
                    return objectMapper.readTree(message.substring(RunService.INFO_PREFIX.length()));
                }
            }
            return null;
        }

        /** 출력에 이 글자가 나올 때까지 기다린다. 걸린 시간(ms)을 돌려준다. */
        long awaitShown(String text, int timeoutSeconds) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

            while (!shown().contains(text)) {
                assertThat(System.currentTimeMillis())
                        .as("'%s' 가 %d초 안에 나오지 않았다. 지금까지의 출력:%n%s", text, timeoutSeconds, shown())
                        .isLessThan(deadline);
                Thread.sleep(100);
            }

            return System.currentTimeMillis() - startedAt;
        }

        Run awaitClosed(int timeoutSeconds) throws InterruptedException {
            assertThat(closed.await(timeoutSeconds, TimeUnit.SECONDS))
                    .as("실행이 끝나고 소켓이 닫혀야 한다. 지금까지의 출력:%n%s", shown())
                    .isTrue();
            return this;
        }

        /** 사용자가 중지를 눌렀다. RunWebSocketHandler 의 STOP 처리와 같은 순서다. */
        void stop() {
            runService.cancel(sessionId);
            dockerService.stopContainer(sessionId);
        }

        /** 사용자가 입력 창에 한 줄을 쳤다. RunWebSocketHandler 의 INPUT 처리와 같다. */
        void input(String line) {
            dockerService.writeToProcess(sessionId, line);
        }
    }

    private Run runConsole() throws Exception {
        return new Run(TemplateType.CONSOLE, "Main.java", false).awaitClosed(180);
    }

    /** 정상 버전을 만들어 둔다(v1 로 한 번 실행). */
    private void establishGreen() throws Exception {
        Files.writeString(live.resolve("Main.java"), MAIN);
        Files.writeString(live.resolve("Helper.java"), HELPER_V1);

        Run first = runConsole();

        assertThat(first.shown()).contains("helper-v1");
        assertThat(first.info().get("mode").asText()).isEqualTo("NO_GREEN");
        assertThat(first.info().get("greenUpdated").asBoolean()).isTrue();
        assertThat(greenService.exists(KEY)).isTrue();
    }

    // ── Java 콘솔 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("팀원이 깨뜨린 파일을 빼고 실행하고, 누가 빠졌는지 알려 준다")
    void excludesTheTeammateWhoBrokeTheBuild() throws Exception {
        establishGreen();

        // 지영이 Helper.java 를 쓰는 중이다(세미콜론이 빠짐). 저장 전이라 상태는 아직 준비됨.
        Files.writeString(live.resolve("Helper.java"), HELPER_BROKEN);
        authorStatusService.recordEdit(KEY, "Helper.java", JIYOUNG);

        Run run = runConsole();

        assertThat(run.shown())
                .contains("helper-v1")
                .contains("회원 3 님이 수정 중인 변경(Helper.java)을 빼고 실행했어요")
                .doesNotContain("error:");

        assertThat(run.info().get("success").asBoolean()).isTrue();
        assertThat(run.info().get("builds").asInt()).isEqualTo(2);
        assertThat(run.info().get("mode").asText()).isEqualTo("EXCLUDED");
        assertThat(run.info().get("excluded").get(0).get("userId").asLong()).isEqualTo(JIYOUNG);
        assertThat(run.info().get("greenUpdated").asBoolean()).isFalse();

        // live 는 그대로이고, 지영의 변경은 장부에 남아 있다.
        assertThat(Files.readString(live.resolve("Helper.java"))).isEqualTo(HELPER_BROKEN);
        assertThat(ledger.authorsOf(KEY, "Helper.java")).containsExactly(JIYOUNG);
    }

    @Test
    @DisplayName("제외한 팀원이 새로 만든 파일은 컨테이너에서도 지워져, 그것에 기대는 코드가 통과하지 못한다")
    void excludedNewFileIsRemovedFromTheReusedContainer() throws Exception {
        establishGreen();

        // 지영이 새 파일 Extra.java 를 만들고 Helper 를 깨뜨렸다. 나는 Main 에서 Extra 를 쓴다.
        Files.writeString(live.resolve("Extra.java"), "public class Extra { static void hi() { } }");
        Files.writeString(live.resolve("Helper.java"), HELPER_BROKEN);
        Files.writeString(live.resolve("Main.java"),
                "public class Main { public static void main(String[] a) { Extra.hi(); Helper.go(); } }");
        authorStatusService.recordEdit(KEY, "Extra.java", JIYOUNG);
        authorStatusService.recordEdit(KEY, "Helper.java", JIYOUNG);
        authorStatusService.recordEdit(KEY, "Main.java", ME);

        Run run = runConsole();

        // 빌드 2 는 지영을 뺀 조합이다. 컨테이너를 재사용하므로, 빌드 1 때 넣은 Extra.java 를
        // 지우지 않으면 "지영을 뺐는데도" Extra 가 있는 것처럼 컴파일돼 버린다.
        assertThat(run.info().get("success").asBoolean()).isFalse();
        assertThat(run.shown()).contains("Main.java").contains("cannot find symbol");
    }

    @Test
    @DisplayName("수정 중(EDITING)으로 판정된 팀원은 처음부터 빼서 빌드 한 번에 끝난다")
    void editingTeammateIsExcludedUpFront() throws Exception {
        establishGreen();

        Files.writeString(live.resolve("Helper.java"), HELPER_BROKEN);
        authorStatusService.recordEdit(KEY, "Helper.java", JIYOUNG);
        authorStatusService.onFileSaved(KEY, "Helper.java", HELPER_BROKEN);

        Run run = runConsole();

        assertThat(run.shown()).contains("helper-v1");
        assertThat(run.info().get("builds").asInt()).isEqualTo(1);
        assertThat(run.info().get("excluded").get(0).get("userId").asLong()).isEqualTo(JIYOUNG);
    }

    @Test
    @DisplayName("모두 준비된 조합으로 성공하면 정상 버전이 갱신되고 장부가 비워진다")
    void promotesGreenWhenEveryoneIsIncluded() throws Exception {
        establishGreen();

        Files.writeString(live.resolve("Helper.java"), HELPER_V2);
        authorStatusService.recordEdit(KEY, "Helper.java", JIYOUNG);

        Run run = runConsole();

        assertThat(run.shown()).contains("helper-v2");
        assertThat(run.info().get("builds").asInt()).isEqualTo(1);
        assertThat(run.info().get("greenUpdated").asBoolean()).isTrue();
        assertThat(Files.readString(paths.green(KEY).resolve("Helper.java"))).isEqualTo(HELPER_V2);
        assertThat(ledger.snapshot(KEY)).isEmpty();
    }

    @Test
    @DisplayName("본인이 깨뜨린 파일은 빼지 않고 에러를 그대로 보여 준다")
    void showsMyOwnError() throws Exception {
        establishGreen();

        Files.writeString(live.resolve("Helper.java"), HELPER_BROKEN);
        authorStatusService.recordEdit(KEY, "Helper.java", ME);

        Run run = runConsole();

        assertThat(run.shown())
                .contains("Helper.java:1: error: ';' expected")
                .contains("컴파일에 실패했어요")
                .doesNotContain("helper-v1");

        assertThat(run.info().get("success").asBoolean()).isFalse();
        assertThat(run.info().get("builds").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("나와 팀원이 함께 고친 파일에서 에러가 나면 되돌리지 않고, 함께 수정 중임을 알린다")
    void sharedFileErrorIsReported() throws Exception {
        establishGreen();

        Files.writeString(live.resolve("Helper.java"), HELPER_BROKEN);
        authorStatusService.recordEdit(KEY, "Helper.java", ME);
        authorStatusService.recordEdit(KEY, "Helper.java", JIYOUNG);

        Run run = runConsole();

        assertThat(run.shown())
                .contains("Helper.java:1: error: ';' expected")
                .contains("Helper.java 은(는) 회원 3 님과 함께 수정 중인 파일이에요");

        assertThat(run.info().get("success").asBoolean()).isFalse();
        assertThat(run.info().get("sharedFiles").get(0).get("file").asText()).isEqualTo("Helper.java");
    }

    @Test
    @DisplayName("누가 고쳤는지 모르는 변경이 깨져 있으면 정상 버전에 내 변경만 더해 실행한다")
    void unknownBrokenChangeFallsBackToOwnOnly() throws Exception {
        establishGreen();

        // 장부에 없는 변경이다(서버 재시작으로 장부가 비었다고 치자).
        Files.writeString(live.resolve("Helper.java"), HELPER_BROKEN);

        Run run = runConsole();

        assertThat(run.shown())
                .contains("helper-v1")
                .contains("마지막 정상 버전에 내 변경만 더해 실행했어요");

        assertThat(run.info().get("builds").asInt()).isEqualTo(2);
        assertThat(run.info().get("mode").asText()).isEqualTo("OWN_ONLY");
    }

    @Test
    @DisplayName("샌드박스 실행은 내 샌드박스 수정을 더해 돌리고, 팀 파일과 정상 버전은 그대로 둔다")
    void sandboxRunUsesMyOverlay() throws Exception {
        establishGreen();

        sandboxService.fork(KEY, ME, "Helper.java", null);
        sandboxService.saveFile(KEY, ME, "Helper.java",
                "public class Helper { static void go() { System.out.println(\"helper-sandbox\"); } }");

        Run run = new Run(TemplateType.CONSOLE, "Main.java", true).awaitClosed(180);

        assertThat(run.shown()).contains("helper-sandbox");
        assertThat(run.info().get("greenUpdated").asBoolean()).isFalse();

        assertThat(Files.readString(live.resolve("Helper.java"))).isEqualTo(HELPER_V1);
        assertThat(Files.readString(paths.green(KEY).resolve("Helper.java"))).isEqualTo(HELPER_V1);

        // 샌드박스를 끄고 실행하면 팀 파일 그대로 돈다.
        assertThat(runConsole().shown()).contains("helper-v1").doesNotContain("helper-sandbox");
    }

    @Test
    @DisplayName("프로그램이 입력을 기다리면 사용자가 친 줄이 전달된다")
    void forwardsStandardInput() throws Exception {
        Files.writeString(live.resolve("Main.java"), """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] a) {
                        System.out.println("name?");
                        System.out.println("hello " + new Scanner(System.in).nextLine());
                    }
                }
                """);

        Run run = new Run(TemplateType.CONSOLE, "Main.java", false);
        run.awaitShown("name?", 120);
        run.input("vside");
        run.awaitClosed(60);

        assertThat(run.shown()).contains("hello vside");
    }

    @Test
    @DisplayName("실행 중에 중지하면 컨테이너가 정리되고 소켓이 닫힌다")
    void stopCleansUp() throws Exception {
        Files.writeString(live.resolve("Main.java"), """
                public class Main {
                    public static void main(String[] a) throws Exception {
                        System.out.println("running");
                        Thread.sleep(600000);
                    }
                }
                """);

        Run run = new Run(TemplateType.CONSOLE, "Main.java", false);
        run.awaitShown("running", 120);

        run.stop();
        run.awaitClosed(30);
    }

    // ── Spring Boot ───────────────────────────────────────────────────

    private void writeBootProject() throws IOException {
        Path src = live.resolve("src/main/java/com/example/demo");
        Files.createDirectories(src);

        // 프로젝트 템플릿(SpringBootTemplateStrategy)이 만드는 것과 같은 구성이다.
        Files.writeString(live.resolve("build.gradle"), """
                plugins { id 'org.springframework.boot' version '3.2.0'; id 'io.spring.dependency-management' version '1.1.4'; id 'java' }
                group = 'com.example'
                repositories { mavenCentral() }
                dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }
                """);
        Files.writeString(live.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(src.resolve("DemoApplication.java"), """
                package com.example.demo;
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class DemoApplication {
                    public static void main(String[] args) { SpringApplication.run(DemoApplication.class, args); }
                }
                """);
        Files.writeString(src.resolve("A.java"), "package com.example.demo;\npublic class A { int a = 1; }\n");
        Files.writeString(src.resolve("B.java"), "package com.example.demo;\npublic class B { int b = 1; }\n");
    }

    private Run runBoot() throws IOException {
        return new Run(TemplateType.SPRING_BOOT, "src/main/java/com/example/demo/DemoApplication.java", false);
    }

    /** 실행 중인 안전 실행 컨테이너가 지금까지 쓴 메모리의 최댓값(MB). 알 수 없으면 -1. */
    private long peakMemoryMb() throws InterruptedException {
        List<String> containers = workbenchContainers();
        if (containers.isEmpty()) {
            return -1;
        }

        String output = dockerService
                .execAndWait(containers.get(0), "cat /sys/fs/cgroup/memory.peak", 10)
                .output().trim();

        return output.matches("\\d+") ? Long.parseLong(output) / (1024 * 1024) : -1;
    }

    @Test
    @DisplayName("[측정] Spring Boot — 빌드 한 번과, 최악의 경우인 빌드 세 번에 걸리는 시간")
    void springBootTimings() throws Exception {
        writeBootProject();

        // 1) 정상 버전이 없는 첫 실행: 빌드 1회.
        Run first = runBoot();
        long firstMs = first.awaitShown("[SERVER_STARTED_PORT:", 600);
        long firstPeak = peakMemoryMb();
        assertThat(first.info().get("builds").asInt()).isEqualTo(1);
        first.stop();
        first.awaitClosed(30);

        // 2) 최악의 경우: 빌드 1 실패 → 빌드 2 실패 → 빌드 3 성공.
        //    지영이 A 를 깨뜨렸고(장부에 있음), 출처를 모르는 변경으로 B 도 깨져 있다.
        Path src = live.resolve("src/main/java/com/example/demo");
        Files.writeString(src.resolve("A.java"), "package com.example.demo;\npublic class A { int a = 1 }\n");
        Files.writeString(src.resolve("B.java"), "package com.example.demo;\npublic class B { int b = 1 }\n");
        authorStatusService.recordEdit(KEY, "src/main/java/com/example/demo/A.java", JIYOUNG);

        Run worst = runBoot();
        long worstMs = worst.awaitShown("[SERVER_STARTED_PORT:", 600);
        long worstPeak = peakMemoryMb();

        assertThat(worst.info().get("builds").asInt()).isEqualTo(3);
        assertThat(worst.info().get("mode").asText()).isEqualTo("OWN_ONLY");
        assertThat(worst.shown()).doesNotContain("error:");

        worst.stop();
        worst.awaitClosed(30);

        System.out.printf(
                "[TIMING] spring-boot 1 build: %.1fs (peak %dMB) | 3 builds (worst case): %.1fs (peak %dMB)%n",
                firstMs / 1000.0, firstPeak, worstMs / 1000.0, worstPeak);
    }

    @Test
    @DisplayName("Spring Boot 는 동시에 하나만 돌고, 뒤에 온 사람은 기다린다는 안내를 받는다")
    void springBootRunsOneAtATime() throws Exception {
        writeBootProject();

        Run first = runBoot();
        first.awaitShown("[SERVER_STARTED_PORT:", 600);

        Run second = runBoot();
        second.awaitShown("[System] 다른 실행이 끝나기를 기다리는 중", 30);

        // 앞사람의 앱이 떠 있는 동안에는 컨테이너를 띄우지도 않는다(메모리를 쓰지 않는다).
        Thread.sleep(3000);
        assertThat(workbenchContainers()).hasSize((int) containersBefore + 1);
        assertThat(second.shown()).doesNotContain("[SERVER_STARTED_PORT:");

        first.stop();
        first.awaitClosed(30);

        second.awaitShown("[SERVER_STARTED_PORT:", 600);
        second.stop();
        second.awaitClosed(30);
    }

    @Test
    @DisplayName("기다리다 중지하면 차례를 가져가지 않는다")
    void cancelledWaiterDoesNotTakeTheSlot() throws Exception {
        writeBootProject();

        Run first = runBoot();
        first.awaitShown("[SERVER_STARTED_PORT:", 600);

        Run waiting = runBoot();
        waiting.awaitShown("[System] 다른 실행이 끝나기를 기다리는 중", 30);
        waiting.stop();

        first.stop();
        first.awaitClosed(30);

        // 기다리던 사람이 그만뒀으니 컨테이너는 하나도 뜨지 않는다(@AfterEach 가 확인한다).
        Thread.sleep(2000);
        assertThat(waiting.shown()).doesNotContain("[SERVER_STARTED_PORT:");
    }
}
