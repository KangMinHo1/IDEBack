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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    private static final String ROOT_PATH = "C:\\WebIDE\\workspaces";
    private final DockerClient dockerClient;

    private final Map<String, PipedOutputStream> runInputMap = new ConcurrentHashMap<>();
    private final Map<String, String> runningContainerMap = new ConcurrentHashMap<>();
    private final Map<String, String> terminalContainerMap = new ConcurrentHashMap<>();
    private final Map<String, PipedOutputStream> terminalInputMap = new ConcurrentHashMap<>();

    // --- [Track 1] Run Project ---
    public void runProject(WebSocketSession session, String workspaceId, String filePath, LanguageType language) {
        String sessionId = session.getId();
        stopContainer(sessionId);

        new Thread(() -> {
            String hostPath = Paths.get(ROOT_PATH, workspaceId).toAbsolutePath().toString();
            String containerId = null;
            PipedOutputStream outToDocker = null;
            try {
                log.info("🚀 워크스페이스 실행: {} (Target File: {})", workspaceId, filePath);

                String targetDir = "";
                String fileName = filePath;

                if (filePath != null && filePath.contains("/")) {
                    targetDir = filePath.substring(0, filePath.lastIndexOf("/"));
                    fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
                }

                String cmdTemplate = language.getRunCommand();
                String runCmd = cmdTemplate.replace("{file}", fileName);
                runCmd = runCmd.replace("src/", "").replace("-cp src", "-cp .").replace("--project src", "--project .");

                String finalCmd = targetDir.isEmpty() ? runCmd : "cd " + targetDir + " && " + runCmd;

                log.info("⚡ Executing Command: {}", finalCmd);

                CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                        .withWorkingDir("/app")
                        .withTty(false).withStdinOpen(true)
                        .withEnv("PYTHONUNBUFFERED=1")
                        .withCmd("sh", "-c", finalCmd)
                        .exec();

                containerId = container.getId();
                runningContainerMap.put(sessionId, containerId);

                dockerClient.copyArchiveToContainerCmd(containerId).withHostResource(hostPath + "/.").withRemotePath("/app").exec();

                outToDocker = new PipedOutputStream();
                runInputMap.put(sessionId, outToDocker);

                AttachContainerResultCallback callback = new AttachContainerResultCallback() {
                    @Override public void onNext(Frame item) { try { if (session.isOpen()) session.sendMessage(new TextMessage(new String(item.getPayload(), StandardCharsets.UTF_8))); } catch (IOException e) {} }
                    @Override public void onError(Throwable throwable) { if (throwable.getMessage() != null && !throwable.getMessage().contains("Pipe closed")) super.onError(throwable); }
                };

                dockerClient.attachContainerCmd(containerId).withStdOut(true).withStdErr(true).withStdIn(new PipedInputStream(outToDocker)).withFollowStream(true).exec(callback);
                dockerClient.startContainerCmd(containerId).exec();
                dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitCompletion();

                if (session.isOpen()) session.sendMessage(new TextMessage("\n--- Execution Finished ---\n"));

            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (!msg.contains("Pipe closed") && !msg.contains("Container died") && !msg.contains("Stream closed")) {
                    log.error("Execution Error", e);
                    try { if (session.isOpen()) session.sendMessage(new TextMessage("\n[Error] " + msg)); } catch (IOException ex) {}
                }
            } finally {
                if (outToDocker != null) { try { runInputMap.remove(sessionId, outToDocker); outToDocker.close(); } catch (IOException e) {} }
                if (containerId != null) { try { runningContainerMap.remove(sessionId, containerId); dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception e) {} }
            }
        }).start();
    }

    // --- [Track 2] Debug Project ---
    public int debugProject(WebSocketSession session, String workspaceId, String filePath, LanguageType language, Consumer<String> logHandler) {
        String sessionId = session.getId();
        stopContainer(sessionId);

        String hostPath = Paths.get(ROOT_PATH, workspaceId).toAbsolutePath().toString();

        ExposedPort tcp5005 = ExposedPort.tcp(5005);
        Ports portBindings = new Ports();
        portBindings.bind(tcp5005, Ports.Binding.bindIp("0.0.0.0"));

        String targetDir = "";
        if (filePath != null && filePath.contains("/")) {
            targetDir = filePath.substring(0, filePath.lastIndexOf("/"));
        }

        String debugCmd = "javac -g -encoding UTF-8 *.java && java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 -cp . Main";
        String finalDebugCmd = targetDir.isEmpty() ? debugCmd : "cd " + targetDir + " && " + debugCmd;

        log.info("🐛 디버그 실행 명령: {}", finalDebugCmd);

        try {
            CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                    .withWorkingDir("/app")
                    .withExposedPorts(tcp5005)
                    .withHostConfig(new HostConfig().withPortBindings(portBindings))
                    .withTty(false).withStdinOpen(true)
                    .withCmd("sh", "-c", finalDebugCmd)
                    .exec();

            String containerId = container.getId();
            runningContainerMap.put(sessionId, containerId);

            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withHostResource(hostPath + "/.")
                    .withRemotePath("/app")
                    .exec();

            PipedOutputStream outToDocker = new PipedOutputStream();
            runInputMap.put(sessionId, outToDocker);

            AttachContainerResultCallback callback = new AttachContainerResultCallback() {
                @Override public void onNext(Frame item) { logHandler.accept(new String(item.getPayload(), StandardCharsets.UTF_8)); }
            };

            dockerClient.attachContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withStdIn(new PipedInputStream(outToDocker))
                    .withFollowStream(true)
                    .exec(callback);

            dockerClient.startContainerCmd(containerId).exec();

            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            int assignedPort = Integer.parseInt(
                    inspect.getNetworkSettings().getPorts().getBindings().get(tcp5005)[0].getHostPortSpec()
            );

            return assignedPort;

        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- [Track 3] Terminal ---
    public void createTerminal(WebSocketSession session, String workspaceId) throws IOException {
        String sessionId = session.getId();
        String hostPath = Paths.get(ROOT_PATH, workspaceId).toAbsolutePath().toString();
        CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env").withTty(true).withStdinOpen(true).withWorkingDir("/app").withCmd("/bin/bash").exec();
        String containerId = container.getId();
        terminalContainerMap.put(sessionId, containerId);
        try { dockerClient.copyArchiveToContainerCmd(containerId).withHostResource(hostPath + "/.").withRemotePath("/app").exec(); } catch(Exception e) {}
        dockerClient.startContainerCmd(containerId).exec();
        PipedOutputStream outToDocker = new PipedOutputStream();
        terminalInputMap.put(sessionId, outToDocker);
        dockerClient.attachContainerCmd(containerId).withStdOut(true).withStdErr(true).withStdIn(new PipedInputStream(outToDocker)).withFollowStream(true).exec(new AttachContainerResultCallback() { @Override public void onNext(Frame item) { try { if (session.isOpen()) session.sendMessage(new TextMessage(new String(item.getPayload(), StandardCharsets.UTF_8))); } catch (IOException e) {} } });
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

    public void writeToTerminal(String sessionId, String command) {
        PipedOutputStream outputStream = terminalInputMap.get(sessionId);
        if (outputStream != null) { try { outputStream.write(command.getBytes(StandardCharsets.UTF_8)); outputStream.flush(); } catch (IOException e) {} }
    }

    public void closeTerminal(String sessionId) {
        String containerId = terminalContainerMap.get(sessionId);
        if (containerId != null) { try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception e) {} finally { terminalContainerMap.remove(sessionId); terminalInputMap.remove(sessionId); } }
    }

    public boolean isContainerAlive(String sessionId) {
        String containerId = runningContainerMap.get(sessionId);
        if (containerId == null) return false;
        try { InspectContainerResponse response = dockerClient.inspectContainerCmd(containerId).exec(); return Boolean.TRUE.equals(response.getState().getRunning()); } catch (Exception e) { return false; }
    }

    // --- [Track 4] Build Support (신규 추가) ---
    // 빌드 실행 후 결과 파일을 호스트로 복사하는 통합 메서드
    public void buildAndCopy(String workspaceId, String command, String containerFilePath, String hostFilePath) {
        String hostWorkspacePath = Paths.get(ROOT_PATH, workspaceId).toAbsolutePath().toString();
        String containerId = null;

        try {
            log.info("🔨 Building... Command: {}", command);

            // 1. 컨테이너 생성
            CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                    .withWorkingDir("/app")
                    .withCmd("sh", "-c", command)
                    .exec();
            containerId = container.getId();

            // 2. 소스 코드 복사
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withHostResource(hostWorkspacePath + "/.")
                    .withRemotePath("/app")
                    .exec();

            // 3. 빌드 실행
            dockerClient.startContainerCmd(containerId).exec();

            // 4. 완료 대기
            dockerClient.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback())
                    .awaitCompletion();

            // 5. 결과 파일 복사 (Container -> Host)
            try (InputStream tarStream = dockerClient.copyArchiveFromContainerCmd(containerId, containerFilePath).exec()) {
                try (TarArchiveInputStream tarIn = new TarArchiveInputStream(tarStream)) {
                    TarArchiveEntry entry;
                    while ((entry = tarIn.getNextTarEntry()) != null) {
                        if (!entry.isDirectory()) {
                            Files.copy(tarIn, Paths.get(hostFilePath), StandardCopyOption.REPLACE_EXISTING);
                            break;
                        }
                    }
                }
            }
            log.info("✅ Build Artifact Copied to: {}", hostFilePath);

        } catch (Exception e) {
            log.error("Build Failed", e);
            throw new RuntimeException("빌드 실패: " + e.getMessage());
        } finally {
            if (containerId != null) {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            }
        }
    }
}