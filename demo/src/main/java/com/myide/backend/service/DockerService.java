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
import com.myide.backend.domain.Workspace;
import com.myide.backend.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    private final DockerClient dockerClient;
    private final WorkspaceRepository workspaceRepository;

    private final Map<String, PipedOutputStream> runInputMap = new ConcurrentHashMap<>();
    private final Map<String, String> runningContainerMap = new ConcurrentHashMap<>();
    private final Map<String, String> terminalContainerMap = new ConcurrentHashMap<>();
    private final Map<String, PipedOutputStream> terminalInputMap = new ConcurrentHashMap<>();

    // 🛠️ [Helper] 경로 계산 로직 (DB 조회 및 브랜치 매핑)
    private String calculateHostPath(String workspaceId, String projectName, String branchName) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("Workspace not found for execution"));

        // [수정] "main"이라고 들어와도 "main-repo" 폴더를 가리키도록 강제 매핑
        // 프론트엔드에서 "main"을 보내더라도 실제 폴더는 "main-repo"이기 때문입니다.
        String realBranchFolder = (branchName == null || branchName.isBlank() ||
                "main-repo".equals(branchName) || "main".equals(branchName))
                ? "main-repo" : branchName;

        // DB에 저장된 path 사용 (예: D:\MyWork\WS1\ProjectA\main-repo)
        return Paths.get(workspace.getPath(), projectName, realBranchFolder).toAbsolutePath().toString();
    }

    // --- [Track 1] Run Project ---
    public void runProject(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, LanguageType language) {
        String sessionId = session.getId();
        stopContainer(sessionId);

        new Thread(() -> {
            try {
                // [수정] DB에서 조회한 실제 경로 사용 (브랜치 매핑 적용됨)
                String hostPath = calculateHostPath(workspaceId, projectName, branchName);

                log.info("🚀 프로젝트 실행: {} (Path: {})", projectName, hostPath);

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

                CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                        .withWorkingDir("/app")
                        .withTty(false).withStdinOpen(true)
                        .withEnv("PYTHONUNBUFFERED=1")
                        .withCmd("sh", "-c", finalCmd)
                        .exec();

                String containerId = container.getId();
                runningContainerMap.put(sessionId, containerId);

                // [중요] 호스트의 브랜치 폴더 내용을 컨테이너 /app 으로 복사
                dockerClient.copyArchiveToContainerCmd(containerId).withHostResource(hostPath + "/.").withRemotePath("/app").exec();

                PipedOutputStream outToDocker = new PipedOutputStream();
                runInputMap.put(sessionId, outToDocker);

                AttachContainerResultCallback callback = new AttachContainerResultCallback() {
                    @Override
                    public void onNext(Frame item) {
                        try {
                            if (session.isOpen()) {
                                session.sendMessage(new TextMessage(new String(item.getPayload(), StandardCharsets.UTF_8)));
                            }
                        } catch (IOException e) {
                            // 클라이언트 연결 끊김 무시
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        // [핵심] 파이프/스트림 종료 에러 무시 (컨테이너 종료 시 자연스러운 현상)
                        if (throwable.getMessage() != null &&
                                (throwable.getMessage().contains("Pipe closed") ||
                                        throwable.getMessage().contains("Stream closed") ||
                                        throwable.getMessage().contains("파이프가 끝났습니다"))) {
                            return;
                        }
                        super.onError(throwable);
                    }
                };

                dockerClient.attachContainerCmd(containerId)
                        .withStdOut(true).withStdErr(true).withStdIn(new PipedInputStream(outToDocker))
                        .withFollowStream(true).exec(callback);

                dockerClient.startContainerCmd(containerId).exec();
                dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitCompletion();

                if (session.isOpen()) session.sendMessage(new TextMessage("\n--- Execution Finished ---\n"));

            } catch (Exception e) {
                // 파이프 에러는 로그만 찍고 넘어감
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (!msg.contains("Pipe closed") && !msg.contains("Stream closed") && !msg.contains("파이프가 끝났습니다") && !msg.contains("Container died")) {
                    log.error("Execution Error", e);
                    try { if (session.isOpen()) session.sendMessage(new TextMessage("\n[Error] " + msg)); } catch (IOException ex) {}
                }
            } finally {
                stopContainer(sessionId);
            }
        }).start();
    }

    // --- [Track 2] Debug Project ---
    public int debugProject(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, LanguageType language, Consumer<String> logHandler) {
        String sessionId = session.getId();
        stopContainer(sessionId);

        // [수정] DB 경로 사용
        String hostPath = calculateHostPath(workspaceId, projectName, branchName);

        ExposedPort tcp5005 = ExposedPort.tcp(5005);
        Ports portBindings = new Ports();
        portBindings.bind(tcp5005, Ports.Binding.bindIp("0.0.0.0"));

        String targetDir = "";
        if (filePath != null && filePath.contains("/")) {
            targetDir = filePath.substring(0, filePath.lastIndexOf("/"));
        }

        String debugCmd = "javac -g -encoding UTF-8 *.java && java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 -cp . Main";
        String finalDebugCmd = targetDir.isEmpty() ? debugCmd : "cd " + targetDir + " && " + debugCmd;

        log.info("🐛 디버그 실행: {} (Path: {})", finalDebugCmd, hostPath);

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
                @Override
                public void onNext(Frame item) { logHandler.accept(new String(item.getPayload(), StandardCharsets.UTF_8)); }
            };

            dockerClient.attachContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true).withStdIn(new PipedInputStream(outToDocker)).withFollowStream(true)
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
    public void createTerminal(WebSocketSession session, String workspaceId, String projectName, String branchName) throws IOException {
        String sessionId = session.getId();

        // [수정] DB 경로 사용
        String hostPath = calculateHostPath(workspaceId, projectName, branchName);

        log.info("💻 터미널 생성: {} (Path: {})", projectName, hostPath);

        CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                .withTty(true).withStdinOpen(true)
                .withWorkingDir("/app")
                .withCmd("/bin/bash")
                .exec();

        String containerId = container.getId();
        terminalContainerMap.put(sessionId, containerId);

        try { dockerClient.copyArchiveToContainerCmd(containerId).withHostResource(hostPath + "/.").withRemotePath("/app").exec(); } catch(Exception e) {}

        dockerClient.startContainerCmd(containerId).exec();

        PipedOutputStream outToDocker = new PipedOutputStream();
        terminalInputMap.put(sessionId, outToDocker);

        dockerClient.attachContainerCmd(containerId)
                .withStdOut(true).withStdErr(true).withStdIn(new PipedInputStream(outToDocker)).withFollowStream(true)
                .exec(new AttachContainerResultCallback() {
                    @Override
                    public void onNext(Frame item) {
                        try {
                            if (session.isOpen()) {
                                session.sendMessage(new TextMessage(new String(item.getPayload(), StandardCharsets.UTF_8)));
                            }
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                    @Override
                    public void onError(Throwable throwable) {
                        // 터미널 종료 시 파이프 에러 무시
                        if (throwable.getMessage() != null &&
                                (throwable.getMessage().contains("Pipe closed") ||
                                        throwable.getMessage().contains("Stream closed") ||
                                        throwable.getMessage().contains("파이프가 끝났습니다"))) {
                            return;
                        }
                        super.onError(throwable);
                    }
                });
    }

    public void stopContainer(String sessionId) {
        String containerId = runningContainerMap.get(sessionId);
        if (containerId != null) {
            try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception e) {}
            runningContainerMap.remove(sessionId);
            runInputMap.remove(sessionId);
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

    // --- [Track 4] Build Support ---
    public void buildAndCopy(String workspaceId, String cmd, String containerFilePath, String hostFilePath) {
        // [수정] 빌드 시 워크스페이스 경로 조회
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("Workspace not found for build"));

        String hostWorkspacePath = workspace.getPath(); // DB 경로 사용

        String containerId = null;
        try {
            log.info("🔨 Building... Command: {}", cmd);
            CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env").withWorkingDir("/app").withCmd("sh", "-c", cmd).exec();
            containerId = container.getId();

            // 워크스페이스 복사
            dockerClient.copyArchiveToContainerCmd(containerId).withHostResource(hostWorkspacePath + "/.").withRemotePath("/app").exec();

            dockerClient.startContainerCmd(containerId).exec();
            dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitCompletion();

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
        } catch (Exception e) {
            throw new RuntimeException("빌드 실패: " + e.getMessage());
        } finally {
            if (containerId != null) dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        }
    }
}