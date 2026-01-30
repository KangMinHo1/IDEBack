package com.myide.backend.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.command.AttachContainerResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.myide.backend.domain.LanguageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    private static final String ROOT_PATH = "C:/ide_projects";
    private final DockerClient dockerClient;

    private final Map<String, PipedOutputStream> runInputMap = new ConcurrentHashMap<>();
    private final Map<String, String> runningContainerMap = new ConcurrentHashMap<>();
    private final Map<String, String> terminalContainerMap = new ConcurrentHashMap<>();
    private final Map<String, PipedOutputStream> terminalInputMap = new ConcurrentHashMap<>();

    // --- [Track 1] Run Project ---
    public void runProject(WebSocketSession session, String userId, String projectName, LanguageType language) {
        String sessionId = session.getId();
        String oldContainerId = runningContainerMap.get(sessionId);
        if (oldContainerId != null) {
            try { dockerClient.removeContainerCmd(oldContainerId).withForce(true).exec(); } catch (Exception e) {}
        }

        new Thread(() -> {
            String hostPath = Paths.get(ROOT_PATH, userId, projectName).toAbsolutePath().toString();
            String containerId = null;
            PipedOutputStream outToDocker = null;
            try {
                log.info("🚀 프로젝트 실행 (Copy Mode): {}", projectName);

                String runCmd = language.getRunCommand()
                        .replace("src/", "")
                        .replace("-cp src", "-cp .")
                        .replace("--project src", "--project .");

                CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                        .withWorkingDir("/app")
                        .withTty(false).withStdinOpen(true)
                        .withEnv("DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1", "DOTNET_CLI_TELEMETRY_OPTOUT=1", "DOTNET_NOLOGO=1", "PYTHONUNBUFFERED=1")
                        .withCmd("sh", "-c", runCmd)
                        .exec();

                containerId = container.getId();
                runningContainerMap.put(sessionId, containerId);

                dockerClient.copyArchiveToContainerCmd(containerId)
                        .withHostResource(hostPath + "/.")
                        .withRemotePath("/app")
                        .exec();

                outToDocker = new PipedOutputStream();
                runInputMap.put(sessionId, outToDocker);

                AttachContainerResultCallback callback = new AttachContainerResultCallback() {
                    @Override public void onNext(Frame item) { try { if (session.isOpen()) session.sendMessage(new TextMessage(new String(item.getPayload(), StandardCharsets.UTF_8))); } catch (IOException e) {} }
                    @Override public void onError(Throwable throwable) { if (throwable.getMessage() != null && !throwable.getMessage().contains("Pipe closed")) super.onError(throwable); }
                };

                dockerClient.attachContainerCmd(containerId).withStdOut(true).withStdErr(true).withStdIn(new PipedInputStream(outToDocker)).withFollowStream(true).exec(callback);

                dockerClient.startContainerCmd(containerId).exec();

                // 컨테이너 종료 대기
                dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitCompletion();

                if (session.isOpen()) {
                    session.sendMessage(new TextMessage("\n--- Execution Finished ---\n"));
                }

            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean isIgnorable = msg.contains("Pipe closed") || msg.contains("Broken pipe") || msg.contains("파이프가 끝났습니다") || msg.contains("Stream closed") || msg.contains("Container died");

                if (!isIgnorable) {
                    log.error("Error", e);
                    try { if (session.isOpen()) session.sendMessage(new TextMessage("\n[Error] " + msg)); } catch (IOException ex) {}
                }
            } finally {
                if (outToDocker != null) { try { runInputMap.remove(sessionId, outToDocker); outToDocker.close(); } catch (IOException e) {} }
                if (containerId != null) { try { runningContainerMap.remove(sessionId, containerId); dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception e) {} }
            }
        }).start();
    }

    // --- [Track 2] Debug Project (랜덤 포트 적용 + 입력 연결) ---
    public int debugProject(WebSocketSession session, String userId, String projectName, LanguageType language, Consumer<String> logHandler) {
        String sessionId = session.getId();
        String oldContainerId = runningContainerMap.get(sessionId);
        if (oldContainerId != null) {
            try { dockerClient.removeContainerCmd(oldContainerId).withForce(true).exec(); } catch (Exception e) {}
        }

        String hostPath = Paths.get(ROOT_PATH, userId, projectName).toAbsolutePath().toString();

        ExposedPort tcp5005 = ExposedPort.tcp(5005);
        Ports portBindings = new Ports();
        // [수정] 랜덤 포트 할당 요청
        portBindings.bind(tcp5005, Ports.Binding.bindIp("0.0.0.0"));

        String debugCmd = "javac -g -encoding UTF-8 *.java && java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 -cp . Main";
        if (language != LanguageType.JAVA) throw new RuntimeException("Java only");

        log.info("🐛 디버그 컨테이너 생성 (Dynamic Port)...");

        CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                .withWorkingDir("/app")
                .withExposedPorts(tcp5005)
                .withHostConfig(new HostConfig().withPortBindings(portBindings))
                .withTty(false).withStdinOpen(true)
                .withCmd("sh", "-c", debugCmd)
                .exec();

        String containerId = container.getId();
        runningContainerMap.put(sessionId, containerId);

        dockerClient.copyArchiveToContainerCmd(containerId)
                .withHostResource(hostPath + "/.")
                .withRemotePath("/app")
                .exec();

        // [수정] 입력 스트림 연결 (Scanner 대응)
        PipedOutputStream outToDocker = new PipedOutputStream();
        runInputMap.put(sessionId, outToDocker); // runInputMap을 공유하여 writeToProcess 재사용

        AttachContainerResultCallback callback = new AttachContainerResultCallback() {
            @Override public void onNext(Frame item) { logHandler.accept(new String(item.getPayload(), StandardCharsets.UTF_8)); }
        };

        try {
            dockerClient.attachContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withStdIn(new PipedInputStream(outToDocker)) // ★ 입력 연결
                    .withFollowStream(true)
                    .exec(callback);
        } catch (IOException e) {
            log.error("Pipe connection failed", e);
        }

        dockerClient.startContainerCmd(containerId).exec();

        // [수정] 할당된 실제 호스트 포트 조회
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        int assignedPort = Integer.parseInt(
                inspect.getNetworkSettings().getPorts().getBindings().get(tcp5005)[0].getHostPortSpec()
        );

        log.info("🐛 디버그 컨테이너 시작됨: {} (Port: {})", containerId, assignedPort);
        return assignedPort;
    }

    public boolean isContainerAlive(String sessionId) {
        String containerId = runningContainerMap.get(sessionId);
        if (containerId == null) return false;
        try {
            InspectContainerResponse response = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(response.getState().getRunning());
        } catch (Exception e) { return false; }
    }

    public void stopContainer(String sessionId) {
        String containerId = runningContainerMap.get(sessionId);
        if (containerId != null) {
            try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception e) {}
            runningContainerMap.remove(sessionId);
        }
    }

    public void writeToProcess(String sessionId, String input) {
        PipedOutputStream outputStream = runInputMap.get(sessionId);
        if (outputStream != null) { try { if (!input.endsWith("\n")) input += "\n"; outputStream.write(input.getBytes(StandardCharsets.UTF_8)); outputStream.flush(); } catch (IOException e) {} }
    }

    // --- [Track 3] Terminal ---
    public void createTerminal(WebSocketSession session, String userId, String projectName) throws IOException {
        String sessionId = session.getId();
        String hostPath = Paths.get(ROOT_PATH, userId, projectName).toAbsolutePath().toString();

        CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                .withTty(true).withStdinOpen(true)
                .withWorkingDir("/app")
                .withCmd("/bin/bash")
                .exec();

        String containerId = container.getId();
        terminalContainerMap.put(sessionId, containerId);

        try {
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withHostResource(hostPath + "/.")
                    .withRemotePath("/app")
                    .exec();
        } catch(Exception e) {}

        dockerClient.startContainerCmd(containerId).exec();

        PipedOutputStream outToDocker = new PipedOutputStream();
        terminalInputMap.put(sessionId, outToDocker);
        dockerClient.attachContainerCmd(containerId).withStdOut(true).withStdErr(true).withStdIn(new PipedInputStream(outToDocker)).withFollowStream(true)
                .exec(new AttachContainerResultCallback() { @Override public void onNext(Frame item) { try { session.sendMessage(new TextMessage(new String(item.getPayload(), StandardCharsets.UTF_8))); } catch (IOException e) {} } });
    }

    public void writeToTerminal(String sessionId, String command) {
        PipedOutputStream outputStream = terminalInputMap.get(sessionId);
        if (outputStream != null) { try { outputStream.write(command.getBytes(StandardCharsets.UTF_8)); outputStream.flush(); } catch (IOException e) {} }
    }
    public void closeTerminal(String sessionId) {
        String containerId = terminalContainerMap.get(sessionId);
        if (containerId != null) { try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception e) {} finally { terminalContainerMap.remove(sessionId); terminalInputMap.remove(sessionId); } }
    }
}