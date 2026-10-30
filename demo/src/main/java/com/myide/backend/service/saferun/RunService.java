// 경로: src/main/java/com/myide/backend/service/saferun/RunService.java
package com.myide.backend.service.saferun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.TemplateType;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.service.DockerService;
import com.myide.backend.service.sandbox.SandboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 안전 실행. 팀원이 코드를 쓰는 중이어도 "돌아가는 조합"을 찾아 실행한다.
 *
 * 한 번의 실행은 빌드를 최대 세 번 시도한다(RunPlanner 가 정한다). 컨테이너는 실행
 * 한 번에 <b>하나만</b> 띄워 끝까지 재사용한다. 시도마다 실행 폴더를 새로 조립해
 * (RunAssembler) 앞선 시도와 달라진 파일만 컨테이너에 바꿔 넣고(RunDelta), 그 안에서
 * 컴파일한다. 성공하면 같은 컨테이너에서 프로그램을 실행하고, 실패하면 다음 조합으로 간다.
 *
 * 컨테이너를 재사용하는 이유는 빌드 비용이다. gradle 은 새 컨테이너에서 처음 돌릴 때
 * 데몬을 띄우고 의존성을 받느라 수십 초가 걸리지만, 같은 컨테이너에서 다시 돌리면 몇 초다.
 *
 * live 폴더는 읽기만 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {

    /** 기계가 읽는 안내 줄의 머리말. 프론트는 이 줄을 출력 창에 찍지 않고 가로채 쓴다. */
    public static final String INFO_PREFIX = "[SAFE_RUN_INFO]";

    /**
     * @param include   사용자가 직접 고른 포함 작성자(고르지 않았으면 빈 집합)
     * @param exclude   사용자가 직접 고른 제외 작성자
     * @param sandbox   샌드박스를 켠 채 실행하는지. 같은 조합 위에 내 샌드박스 파일을 덮는다
     * @param wantsInfo 기계용 안내 줄([SAFE_RUN_INFO])을 받을지. 받겠다고 한 클라이언트에게만
     *                  보낸다. 모르는 클라이언트는 그 줄을 출력 창에 그대로 찍어 버린다
     */
    public record Request(
            BranchKey key,
            String filePath,
            LanguageType language,
            TemplateType templateType,
            Long requester,
            Set<Long> include,
            Set<Long> exclude,
            boolean sandbox,
            boolean wantsInfo
    ) {}

    private final DockerService dockerService;
    private final SafeRunPaths paths;
    private final EditLedger ledger;
    private final AuthorStatusService authorStatusService;
    private final RunAssembler assembler;
    private final GreenService greenService;
    private final SafeRunBroadcaster broadcaster;
    private final SandboxService sandboxService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /* 유형별 최대 빌드 횟수. 2 로 낮추면 "빌드 1 → 빌드 3"만 한다. */
    @Value("${ide.safe-run.max-builds.console:3}")
    private int consoleMaxBuilds;

    @Value("${ide.safe-run.max-builds.spring-boot:3}")
    private int springBootMaxBuilds;

    @Value("${ide.safe-run.build-timeout-seconds:600}")
    private long buildTimeoutSeconds;

    /*
     * 서버 전체에서 동시에 돌 수 있는 Spring Boot 안전 실행의 수. 하나가 700MB 넘게 쓴다.
     * 운영 서버를 4GB 로 늘려 3 으로 올렸다. 메모리 부족으로 서버가 죽으면 이 값을 내린다.
     */
    @Value("${ide.safe-run.spring-boot-concurrency:3}")
    private int springBootConcurrency;

    private RunQueue springBootQueue;

    /** 세션별 "중지됨" 깃발. 사용자가 중지를 누르면 다음 시도로 넘어가지 않게 한다. */
    private final Map<String, AtomicBoolean> activeRuns = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        springBootQueue = new RunQueue(springBootConcurrency);

        // 서버가 실행 도중 죽었다면 임시 폴더가 남아 있다. 시작할 때 치운다.
        try {
            RunAssembler.deleteRecursively(paths.runsRoot());
        } catch (IOException e) {
            log.warn("⚠️ [SafeRun] 남은 실행 폴더 청소 실패: {}", e.getMessage());
        }
    }

    /** 실행을 시작한다. 빌드가 오래 걸리므로 소켓 스레드를 붙잡지 않고 바로 돌아온다. */
    public void start(WebSocketSession session, Request request) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean previous = activeRuns.put(session.getId(), cancelled);

        if (previous != null) {
            previous.set(true);
        }

        Thread worker = new Thread(() -> {
            try {
                runQueued(session, request, cancelled);
            } catch (Exception e) {
                log.error("❌ [SafeRun] 실행 실패", e);
                send(session, "\n[Error] 안전 실행 중 오류가 발생했습니다. " + e.getMessage() + "\n");
                finish(session);
            } finally {
                activeRuns.remove(session.getId(), cancelled);
            }
        }, "safe-run-" + session.getId());

        worker.setDaemon(true);
        worker.start();
    }

    /** 사용자가 중지를 눌렀거나 소켓이 닫혔다. 컨테이너는 DockerService.stopContainer 가 치운다. */
    public void cancel(String sessionId) {
        AtomicBoolean cancelled = activeRuns.remove(sessionId);

        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    /**
     * Spring Boot 는 차례를 기다렸다가 실행한다. 자리는 앱이 내려갈 때까지 쥐고 있는다.
     * Java 콘솔은 가벼워서 기다리지 않는다.
     */
    private void runQueued(WebSocketSession session, Request request, AtomicBoolean cancelled) throws Exception {
        if (request.templateType() != TemplateType.SPRING_BOOT) {
            run(session, request, cancelled, () -> {});
            return;
        }

        boolean acquired = springBootQueue.acquire(
                () -> cancelled.get() || !session.isOpen(),
                () -> send(session, "[System] 다른 실행이 끝나기를 기다리는 중\n")
        );

        if (!acquired) {
            return;
        }

        // 끝을 알리기 전에 자리를 먼저 돌려준다. 화면은 끝 알림을 받자마자 Run 을 다시 켜므로,
        // 정리를 다 마친 뒤에 돌려주면 바로 다시 누른 사람이 자기 이전 실행을 기다리게 된다.
        // 두 번 돌려주면 자리가 늘어나므로 한 번만 돌려준다.
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable releaseSlot = () -> {
            if (released.compareAndSet(false, true)) {
                springBootQueue.release();
            }
        };

        try {
            run(session, request, cancelled, releaseSlot);
        } finally {
            releaseSlot.run();
        }
    }

    private void run(WebSocketSession session, Request request, AtomicBoolean cancelled, Runnable releaseSlot)
            throws Exception {
        BranchKey key = request.key();
        boolean isSpringBoot = request.templateType() == TemplateType.SPRING_BOOT;

        // 실행 도중에도 편집은 계속 들어온다. 조립과 판단이 같은 장부를 보도록 사본을 뜬다.
        // 본인이 고친 뒤 다른 사람이 이어서 고친 파일은 그 사람의 파일로 본다(snapshotFor).
        Map<String, Set<Long>> ledgerSnapshot = ledger.snapshotFor(key, request.requester());

        RunPlanner planner = new RunPlanner(
                ledgerSnapshot,
                request.requester(),
                greenService.exists(key),
                isSpringBoot ? springBootMaxBuilds : consoleMaxBuilds
        );

        Set<Long> ready = authorStatusService.statuses(key).entrySet().stream()
                .filter(entry -> entry.getValue() == AuthorStatus.READY)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        RunCommands.Commands commands =
                RunCommands.forTemplate(request.templateType(), request.language(), request.filePath());

        RunPlanner.Attempt attempt = planner.first(ready, request.include(), request.exclude());

        // 샌드박스 실행이면 어느 조합이든 맨 위에 내 샌드박스 파일을 덮는다.
        SandboxService.Overlay overlay = request.sandbox()
                ? sandboxService.overlay(key, request.requester())
                : null;

        // 이 실행이 끝날 때까지 쓰는 컨테이너 하나와, 그동안 만든 임시 폴더들.
        // Spring Boot 는 창(Swing)을 띄울 수도 있어 화면 중계용 포트를 미리 열어 둔다.
        DockerService.Workbench bench =
                dockerService.openWorkbench(session, request.templateType().getDefaultPort(), isSpringBoot);
        List<Path> tempDirs = new ArrayList<>();

        try {
            Path previousRunDir = null;

            while (attempt != null && !cancelled.get()) {
                Path runDir = paths.run(UUID.randomUUID().toString());
                tempDirs.add(runDir);

                assembler.assemble(new RunAssembler.Spec(
                        paths.green(key),
                        paths.live(key),
                        runDir,
                        ledgerSnapshot,
                        request.requester(),
                        attempt.included(),
                        attempt.includeUnknownChanges(),
                        overlay == null ? null : overlay.files(),
                        overlay == null ? null : overlay.deleted()
                ));

                syncIntoContainer(bench.containerId(), previousRunDir, runDir, tempDirs);
                previousRunDir = runDir;

                DockerService.ExecResult build = dockerService.execAndWait(
                        bench.containerId(), commands.compileShell(), buildTimeoutSeconds);

                if (cancelled.get()) {
                    return;
                }

                if (build.exitCode() == 0) {
                    boolean greenUpdated = false;

                    // 샌드박스 실행은 정상 버전으로 삼지 않는다. 팀에 아직 없는 내 수정이 섞여 있다.
                    if (!request.sandbox() && planner.isPromotable(attempt)) {
                        greenUpdated = promoteGreen(key, runDir);
                    }

                    announceSuccess(session, request, planner, attempt, greenUpdated);

                    // 컴파일러 경고는 기존 실행에서도 보이던 것이라 그대로 보여 준다.
                    if (!build.output().isBlank()) {
                        send(session, build.output().stripTrailing() + "\n");
                    }

                    // 실제로 실행될 조합에 창 코드가 있는지 본다. 임시 폴더를 지우기 전에 봐야 한다.
                    boolean desktopUi = bench.guiHostPort() > 0 && DesktopUiDetector.usesDesktopUi(runDir);

                    // bootRun 은 시작 클래스의 main 만 돌리므로, 창 클래스의 main 은 따로 띄운다.
                    String windowClass = desktopUi && isSpringBoot
                            ? DesktopWindowLauncher.findWindowClass(runDir, request.filePath()).orElse(null)
                            : null;

                    // 컨테이너에 다 들어갔으니 임시 폴더는 프로그램이 도는 동안 붙들고 있을 이유가 없다.
                    deleteAll(tempDirs);

                    runProgram(session, bench, commands, desktopUi && startDesktop(session, bench), windowClass,
                            cancelled);
                    releaseSlot.run();
                    finish(session);
                    return;
                }

                Set<String> errorFiles = CompileErrorParser.errorFiles(build.output(), commands.workingDir());

                // 음수면 컴파일 실패가 아니라 명령이 끝까지 돌지 못한 것이다(시간 초과 등).
                // 조합을 바꿔도 같으므로 더 시도하지 않는다.
                RunPlanner.Attempt next = build.exitCode() < 0
                        ? null
                        : planner.afterFailure(attempt, errorFiles);

                if (next == null) {
                    if (build.exitCode() < 0) {
                        send(session, "[System] 빌드가 끝나기 전에 실행 환경이 종료됐어요\n");
                    }

                    announceFailure(session, request, planner, attempt, build.output(), errorFiles);
                    releaseSlot.run();
                    finish(session);
                    return;
                }

                attempt = next;
            }
        } finally {
            // 성공·실패·중지·예외 어느 경우든 컨테이너와 임시 폴더를 남기지 않는다.
            dockerService.closeWorkbench(session.getId(), bench.containerId());
            deleteAll(tempDirs);
        }
    }

    /**
     * 조립한 실행 폴더를 컨테이너에 넣는다. 첫 시도는 통째로, 그 뒤로는 앞선 시도와
     * 달라진 파일만 바꿔 넣는다. 통째로 다시 넣으면 gradle 이 전부 새로 컴파일한다.
     */
    private void syncIntoContainer(String containerId, Path previousRunDir, Path runDir, List<Path> tempDirs)
            throws IOException, InterruptedException {

        if (previousRunDir == null) {
            dockerService.copyInto(containerId, runDir);
            return;
        }

        RunDelta delta = RunDelta.between(previousRunDir, runDir);

        if (!delta.changed().isEmpty()) {
            Path deltaDir = paths.run(UUID.randomUUID() + "-delta");
            tempDirs.add(deltaDir);

            delta.copyChangedTo(runDir, deltaDir);
            dockerService.copyInto(containerId, deltaDir);
        }

        if (!delta.removed().isEmpty()) {
            dockerService.execAndWait(containerId, delta.removeShell(), 60);
        }
    }

    /** 컴파일에 성공한 실행 폴더를 정상 버전으로 삼는다. 성공했으면 true. */
    private boolean promoteGreen(BranchKey key, Path runDir) {
        try {
            greenService.promote(key, runDir);
        } catch (IOException e) {
            // 정상 버전 갱신은 덤이다. 실패했다고 이미 성공한 실행을 막지 않는다.
            log.warn("⚠️ [SafeRun] 정상 버전 갱신 실패: {}", e.getMessage());
            return false;
        }

        authorStatusService.refresh(key);
        broadcaster.greenUpdated(key);
        return true;
    }

    /**
     * 창을 그릴 가짜 모니터를 프로그램에 알려 준다.
     *
     * Spring Boot 는 스스로 "화면 없음"(java.awt.headless=true)을 켜는데, 값이 이미 있으면
     * 덮어쓰지 않는다. 그래서 사용자 코드를 고치지 않고 여기서 꺼 준다. 컨테이너 전체가 아니라
     * 실행 명령에만 붙이는 이유는, 창을 안 쓰는 실행의 출력에 JVM 의 "Picked up
     * JAVA_TOOL_OPTIONS" 줄이 끼지 않게 하려는 것이다.
     */
    private static final String DESKTOP_ENV =
            "export DISPLAY=:99 JAVA_TOOL_OPTIONS=-Djava.awt.headless=false; ";

    /**
     * 컨테이너 안에 가짜 모니터와 화면 중계를 켠다. 켰으면 true.
     *
     * 프론트는 [GUI_STARTED_PORT:n] 을 보고 미리보기에 "앱 화면" 탭을 띄운다. 실행 이미지가
     * 예전 것이라 화면 기능이 없으면 그 탭이 빈 화면으로 뜨므로, 신호를 보내지 않고 알려만 준다.
     * 프로그램은 그대로 실행된다(API 는 쓸 수 있다).
     */
    private boolean startDesktop(WebSocketSession session, DockerService.Workbench bench)
            throws InterruptedException {

        DockerService.ExecResult started = dockerService.execAndWait(bench.containerId(), "ide-gui-start", 30);

        if (started.exitCode() != 0) {
            send(session, "[System] 화면(Swing) 코드가 있지만 실행 환경에 화면 기능이 없어요. "
                    + "ide-execution-env 이미지를 다시 빌드해 주세요\n");
            return false;
        }

        send(session, "[System] 화면(Swing) 코드를 찾아 앱 화면을 함께 켰어요\n");
        send(session, "\n[GUI_STARTED_PORT:" + bench.guiHostPort() + "]\n");
        return true;
    }

    /** 컴파일이 성공했다. 같은 컨테이너에서 프로그램을 실행하고 끝날 때까지 기다린다. */
    private void runProgram(WebSocketSession session, DockerService.Workbench bench, RunCommands.Commands commands,
                            boolean desktopUi, String windowClass, AtomicBoolean cancelled)
            throws IOException, InterruptedException {

        CountDownLatch ended = new CountDownLatch(1);

        String runShell = commands.runShell();

        if (desktopUi) {
            String launchWindow = "";

            if (windowClass != null) {
                launchWindow = DesktopWindowLauncher.launchShell(
                        // 창 클래스는 Spring Boot 일 때만 고른다. 서버는 컨테이너 안에서 이 포트로 뜬다.
                        windowClass, windowClasspath(session, bench), TemplateType.SPRING_BOOT.getDefaultPort());
                send(session, "[System] 서버가 뜨면 " + windowClass + " 창을 따로 띄워요\n");
            }

            runShell = DESKTOP_ENV + launchWindow + runShell;
        }

        dockerService.execStreaming(session, bench.containerId(), runShell, new DockerService.RunOutput() {
            @Override
            public void onOutput(String chunk) {
                send(session, chunk);
            }

            @Override
            public void onClosed() {
                ended.countDown();
            }
        });

        if (bench.hostPort() > 0) {
            // 서버형은 계속 떠 있는다. 기존 실행과 같은 신호로 미리보기를 띄우게 한다.
            send(session, "\n[SERVER_STARTED_PORT:" + bench.hostPort() + "]\n");
        }

        // 프로그램이 스스로 끝나거나, 사용자가 중지해 컨테이너가 사라질 때까지.
        // Spring Boot 는 여기서 기다리는 동안 대기열의 자리를 쥐고 있다.
        // Docker 의 종료 알림만 믿으면 그 알림이 빠졌을 때 자리가 영영 안 풀린다. 중지(또는 창 닫기)도 함께 본다.
        while (!ended.await(1, TimeUnit.SECONDS)) {
            if (cancelled.get()) {
                return;
            }
        }
    }

    /**
     * 창을 띄울 때 쓸 라이브러리 목록. 서버(bootRun)를 켜기 전에 묻는다 — 같은 프로젝트에 gradle 을
     * 겹쳐 돌리면 서로 잠금을 기다린다. 못 받으면 프로젝트 클래스만으로 띄운다(라이브러리를 안 쓰는 창은 뜬다).
     */
    private String windowClasspath(WebSocketSession session, DockerService.Workbench bench)
            throws InterruptedException {

        DockerService.ExecResult result = dockerService.execAndWait(
                bench.containerId(), DesktopWindowLauncher.classpathShell(), buildTimeoutSeconds);

        return DesktopWindowLauncher.parseClasspath(result.output()).orElseGet(() -> {
            send(session, "[System] 라이브러리 목록을 받지 못해 프로젝트 코드만으로 창을 띄워요. "
                    + "창에서 라이브러리를 쓰면 실행 중 오류가 날 수 있어요\n");
            return DesktopWindowLauncher.FALLBACK_CLASSPATH;
        });
    }

    private void announceSuccess(
            WebSocketSession session,
            Request request,
            RunPlanner planner,
            RunPlanner.Attempt attempt,
            boolean greenUpdated
    ) {
        Set<Long> excluded = planner.excludedAuthors(attempt);
        Map<Long, String> nicknames = nicknamesOf(excluded);
        List<Map<String, Object>> excludedInfo = new ArrayList<>();

        for (Long author : excluded) {
            List<String> files = planner.revertedFilesOf(author, attempt);

            // 본인과 함께 고친 파일뿐이라 실제로 빠진 것이 없으면 안내하지 않는다.
            if (files.isEmpty()) {
                continue;
            }

            send(session, "[System] " + nicknames.get(author) + " 님이 수정 중인 변경("
                    + String.join(", ", files) + ")을 빼고 실행했어요\n");

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("userId", author);
            entry.put("nickname", nicknames.get(author));
            entry.put("files", files);
            excludedInfo.add(entry);
        }

        if (attempt.mode() == RunPlanner.Mode.OWN_ONLY) {
            send(session, "[System] 마지막 정상 버전에 내 변경만 더해 실행했어요\n");
        }

        Map<String, Object> info = baseInfo(true, attempt);
        info.put("excluded", excludedInfo);
        info.put("sharedFiles", List.of());
        info.put("errorFiles", List.of());
        info.put("greenUpdated", greenUpdated);
        sendInfo(session, request, info);
    }

    private void announceFailure(
            WebSocketSession session,
            Request request,
            RunPlanner planner,
            RunPlanner.Attempt attempt,
            String compileOutput,
            Set<String> errorFiles
    ) {
        if (!compileOutput.isBlank()) {
            send(session, compileOutput + "\n");
        }

        send(session, "[System] 컴파일에 실패했어요\n");

        List<RunPlanner.SharedFile> shared = planner.sharedFiles(errorFiles);
        Set<Long> coAuthors = new TreeSet<>();
        shared.forEach(file -> coAuthors.addAll(file.coAuthors()));

        Map<Long, String> nicknames = nicknamesOf(coAuthors);
        List<Map<String, Object>> sharedInfo = new ArrayList<>();

        for (RunPlanner.SharedFile file : shared) {
            String names = file.coAuthors().stream()
                    .map(nicknames::get)
                    .collect(Collectors.joining(", "));

            // 본인 변경을 몰래 빼지 않으려고 이 파일은 되돌리지 않았다. 그래서 에러가
            // 남았다는 것을 알려 준다.
            send(session, "[System] " + file.file() + " 은(는) " + names + " 님과 함께 수정 중인 파일이에요\n");

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", file.file());
            entry.put("coAuthors", file.coAuthors().stream().map(author -> {
                Map<String, Object> coAuthor = new LinkedHashMap<>();
                coAuthor.put("userId", author);
                coAuthor.put("nickname", nicknames.get(author));
                return coAuthor;
            }).toList());
            sharedInfo.add(entry);
        }

        Map<String, Object> info = baseInfo(false, attempt);
        info.put("excluded", List.of());
        info.put("sharedFiles", sharedInfo);
        info.put("errorFiles", List.copyOf(errorFiles));
        info.put("greenUpdated", false);
        sendInfo(session, request, info);
    }

    private Map<String, Object> baseInfo(boolean success, RunPlanner.Attempt attempt) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("success", success);
        info.put("builds", attempt.number());
        info.put("mode", attempt.mode().name());
        return info;
    }

    private void sendInfo(WebSocketSession session, Request request, Map<String, Object> info) {
        if (!request.wantsInfo()) {
            return;
        }

        try {
            // 한 메시지에 이 줄만 담아 보낸다. 받는 쪽이 메시지 단위로 가려낼 수 있다.
            send(session, INFO_PREFIX + objectMapper.writeValueAsString(info) + "\n");
        } catch (Exception e) {
            log.warn("⚠️ [SafeRun] 안내 전송 실패: {}", e.getMessage());
        }
    }

    private Map<Long, String> nicknamesOf(Set<Long> userIds) {
        Map<Long, String> nicknames = new TreeMap<>();
        userIds.forEach(userId -> nicknames.put(userId, "회원 " + userId));

        for (User user : userRepository.findAllById(userIds)) {
            nicknames.put(user.getId(), user.getNickname());
        }

        return nicknames;
    }

    /** 기존 콘솔 실행과 같은 방식으로 끝을 알리고 소켓을 닫는다. */
    private void finish(WebSocketSession session) {
        send(session, "\n--- Execution Finished ---\n");

        try {
            Thread.sleep(100);

            if (session.isOpen()) {
                session.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** 출력 스레드와 실행 흐름 스레드가 같은 소켓에 쓰므로 한 번에 하나씩만 보낸다. */
    private void send(WebSocketSession session, String text) {
        synchronized (session) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(text));
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void deleteAll(List<Path> dirs) {
        dirs.forEach(this::deleteQuietly);
        dirs.clear();
    }

    private void deleteQuietly(Path runDir) {
        try {
            RunAssembler.deleteRecursively(runDir);
        } catch (IOException e) {
            log.warn("⚠️ [SafeRun] 실행 폴더 삭제 실패: {} ({})", runDir, e.getMessage());
        }
    }
}
