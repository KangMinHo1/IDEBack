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

    // 💡 [원상 복구] 일반 실행(Run) 시 스캐너 입력을 받기 위한 파이프라인 맵 (복구완료!)
    private final Map<String, PipedOutputStream> runInputMap = new ConcurrentHashMap<>();
    private final Map<String, String> runningContainerMap = new ConcurrentHashMap<>();
    private final Map<String, String> terminalContainerMap = new ConcurrentHashMap<>();
    private final Map<String, PipedOutputStream> terminalInputMap = new ConcurrentHashMap<>();

    private String calculateHostPath(String workspaceId, String projectName, String branchName) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("Workspace not found for execution"));
        String realBranchFolder = (branchName == null || branchName.isBlank() || "main-repo".equals(branchName) || "main".equals(branchName))
                ? "main-repo" : branchName;
        return Paths.get(workspace.getPath(), projectName, realBranchFolder).toAbsolutePath().toString();
    }

    // --- [Track 1] Run Project (일반 실행) ---
    public void runProject(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, LanguageType language) {
        String sessionId = session.getId();
        stopContainer(sessionId);

        new Thread(() -> {
            try {
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

                dockerClient.copyArchiveToContainerCmd(containerId).withHostResource(hostPath + "/.").withRemotePath("/app").exec();

                // 💡 [원상 복구] 일반 실행용 스캐너 입력 파이프라인
                PipedOutputStream outToDocker = new PipedOutputStream();
                runInputMap.put(sessionId, outToDocker);

                AttachContainerResultCallback callback = new AttachContainerResultCallback() {
                    @Override
                    public void onNext(Frame item) {
                        try {
                            if (session.isOpen()) {
                                session.sendMessage(new TextMessage(new String(item.getPayload(), StandardCharsets.UTF_8)));
                            }
                        } catch (IOException e) {}
                    }
                    @Override
                    public void onError(Throwable throwable) {
                        if (throwable.getMessage() != null &&
                                (throwable.getMessage().contains("Pipe closed") ||
                                        throwable.getMessage().contains("Stream closed") ||
                                        throwable.getMessage().contains("파이프가 끝났습니다"))) {
                            return;
                        }
                        super.onError(throwable);
                    }
                };

                // 스캐너 입력을 위해 PipedInputStream 연결
                dockerClient.attachContainerCmd(containerId)
                        .withStdOut(true).withStdErr(true).withStdIn(new PipedInputStream(outToDocker))
                        .withFollowStream(true).exec(callback);

                dockerClient.startContainerCmd(containerId).exec();
                dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitCompletion();

                if (session.isOpen()) session.sendMessage(new TextMessage("\n--- Execution Finished ---\n"));

            } catch (Exception e) {
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

    // --- [Track 2] Debug Project (자바 디버그) ---
    public int debugProject(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, LanguageType language, Consumer<String> logHandler) {
        String sessionId = session.getId();
        stopContainer(sessionId);

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

            dockerClient.copyArchiveToContainerCmd(containerId).withHostResource(hostPath + "/.").withRemotePath("/app").exec();

            // 자바 디버깅은 Scanner 입력을 받을 수 있으므로 파이프라인 유지
            PipedOutputStream outToDocker = new PipedOutputStream();
            runInputMap.put(sessionId, outToDocker);

            AttachContainerResultCallback callback = new AttachContainerResultCallback() {
                @Override
                public void onNext(Frame item) { logHandler.accept(new String(item.getPayload(), StandardCharsets.UTF_8)); }
            };

            dockerClient.attachContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true).withStdIn(new PipedInputStream(outToDocker))
                    .withFollowStream(true).exec(callback);

            dockerClient.startContainerCmd(containerId).exec();

            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return Integer.parseInt(inspect.getNetworkSettings().getPorts().getBindings().get(tcp5005)[0].getHostPortSpec());

        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- [Track 3] Terminal ---
    public void createTerminal(WebSocketSession session, String workspaceId, String projectName, String branchName) throws IOException {
        String sessionId = session.getId();
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
                        } catch (IOException e) {}
                    }
                    @Override
                    public void onError(Throwable throwable) {
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

    // --- DockerService.java 내부 ---

    // 💡 [최종 수정] 컨테이너 종료 시, 파이프라인까지 완벽하게 분쇄하여 다음 디버깅에 영향을 주지 않도록 청소합니다!
    public void stopContainer(String sessionId) {
        // 1. 실행 중인 컨테이너 맵에서 제거
        String containerId = runningContainerMap.remove(sessionId);

        if (containerId != null) {
            try {
                // 도커 컨테이너 강제 파괴
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                log.warn("컨테이너 강제 종료 중 오류 (이미 종료되었을 수 있음): {}", e.getMessage());
            }
        }

        // 2. 🚨 [핵심] 파이프라인(PipedOutputStream) 완벽 청소!
        // 이 파이프가 열려있으면 다음 파이썬 디버깅 때 Pipe Broken이 발생합니다.
        PipedOutputStream outputStream = runInputMap.remove(sessionId);
        if (outputStream != null) {
            try {
                // 파이프에 남은 찌꺼기를 밀어내고 완전히 닫아버립니다.
                outputStream.flush();
                outputStream.close();
            } catch (IOException e) {
                // 이미 닫힌 파이프라면 무시
            }
        }
    }

    // 💡 [가장 핵심!!!] JS와 파이썬의 디버깅 명령어(step over 등)를 에러 없이 꽂아 넣는 마법의 메서드!
    public void writeToProcess(String sessionId, String input) {
        String containerId = runningContainerMap.get(sessionId);

        if (containerId != null) {
            // 1. 일반 실행(스캐너 입력) 중일 경우: 기존의 파이프라인(PipedOutputStream)을 사용합니다.
            PipedOutputStream outputStream = runInputMap.get(sessionId);
            if (outputStream != null) {
                try {
                    if (!input.endsWith("\n")) input += "\n";
                    outputStream.write(input.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                    return; // 파이프로 잘 들어갔으면 여기서 종료!
                } catch (IOException e) {
                    log.warn("파이프 입력 실패, docker exec로 대체합니다.");
                }
            }

            // 2. 파이프가 없거나 터진 경우 (주로 JS, Python 디버깅 시): docker exec로 우회해서 꽂아 넣습니다!
            try {
                if (!input.endsWith("\n")) input += "\n";
                // 컨테이너 메인 프로세스(PID 1)의 입력 포트(fd/0)에 강제 입력
                String[] command = {"sh", "-c", "echo '" + input.replace("'", "'\\''") + "' > /proc/1/fd/0"};

                String execId = dockerClient.execCreateCmd(containerId)
                        .withCmd(command)
                        .withAttachStdout(false)
                        .withAttachStderr(false)
                        .exec().getId();

                dockerClient.execStartCmd(execId).exec(new AttachContainerResultCallback()).awaitCompletion();
            } catch (Exception e) {
                log.error("도커 exec 입력 전송 실패", e);
            }
        }
    }

    public void writeToTerminal(String sessionId, String command) {
        PipedOutputStream outputStream = terminalInputMap.get(sessionId);
        if (outputStream != null) {
            try {
                outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException e) {}
        }
    }

    public void closeTerminal(String sessionId) {
        String containerId = terminalContainerMap.get(sessionId);
        if (containerId != null) {
            try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception e) {}
            finally { terminalContainerMap.remove(sessionId); terminalInputMap.remove(sessionId); }
        }
    }

    public boolean isContainerAlive(String sessionId) {
        String containerId = runningContainerMap.get(sessionId);
        if (containerId == null) return false;
        try {
            InspectContainerResponse response = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(response.getState().getRunning());
        } catch (Exception e) { return false; }
    }

    // --- [Track 4] Build Support ---
    public void buildAndCopy(String workspaceId, String cmd, String containerFilePath, String hostFilePath) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("Workspace not found for build"));

        String hostWorkspacePath = workspace.getPath();
        String containerId = null;
        try {
            log.info("🔨 Building... Command: {}", cmd);
            CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env").withWorkingDir("/app").withCmd("sh", "-c", cmd).exec();
            containerId = container.getId();

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

    // --- [Track 5] Python Debug Project ---
    public void debugPython(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, Consumer<String> outputCallback) {
        String sessionId = session.getId();
        stopContainer(sessionId);

        new Thread(() -> {
            try {
                String hostPath = calculateHostPath(workspaceId, projectName, branchName);
                String targetDir = ""; String fileName = filePath;
                if (filePath != null && filePath.contains("/")) {
                    targetDir = filePath.substring(0, filePath.lastIndexOf("/"));
                    fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
                }

                String runCmd = "python3 -u -m pdb " + fileName;
                String finalCmd = targetDir.isEmpty() ? runCmd : "cd " + targetDir + " && " + runCmd;

                log.info("🐍 파이썬 디버그 컨테이너 생성: {}", finalCmd);

                CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                        .withWorkingDir("/app")
                        .withTty(false).withStdinOpen(true)
                        .withCmd("sh", "-c", finalCmd)
                        .exec();

                String containerId = container.getId();
                runningContainerMap.put(sessionId, containerId);

                dockerClient.copyArchiveToContainerCmd(containerId).withHostResource(hostPath + "/.").withRemotePath("/app").exec();

                // 💡💡 [여기서부터 2줄 추가!!!] 💡💡
                // 파이썬이 "인터폰이 없네?" 하고 퇴근하지 않도록,
                // 허공에 연결된 빈 인터폰(PipedOutputStream)이라도 하나 만들어서 물려줍니다!
                PipedOutputStream fakeIntercom = new PipedOutputStream();
                PipedInputStream fakeReceiver = new PipedInputStream(fakeIntercom);

                AttachContainerResultCallback callback = new AttachContainerResultCallback() {
                    @Override
                    public void onNext(Frame item) {
                        outputCallback.accept(new String(item.getPayload(), StandardCharsets.UTF_8));
                    }
                    @Override
                    public void onError(Throwable throwable) {
                        if (throwable.getMessage() != null && (throwable.getMessage().contains("Pipe closed") || throwable.getMessage().contains("Stream closed"))) return;
                        super.onError(throwable);
                    }
                };

                // 💡💡 [여기 1줄 수정!!!] 💡💡
                // .withStdIn() 에 우리가 방금 만든 가짜 인터폰(fakeReceiver)을 달아줍니다!
                dockerClient.attachContainerCmd(containerId)
                        .withStdOut(true).withStdErr(true)
                        .withStdIn(fakeReceiver) // <--- 이것만 추가하시면 됩니다!
                        .withFollowStream(true).exec(callback);

                dockerClient.startContainerCmd(containerId).exec();
                dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitCompletion();

            } catch (Exception e) {
                log.error("Python Debug Error", e);
            } finally {
                stopContainer(sessionId);
            }
        }).start();
    }

    // --- [Track 5] 만능 CLI 디버깅 지원 (JS) ---
    public void debugWithCli(WebSocketSession session, String workspaceId, String projectName, String branchName, String debugCmd, Consumer<String> outputCallback) {
        String sessionId = session.getId();
        stopContainer(sessionId);

        new Thread(() -> {
            try {
                String hostPath = calculateHostPath(workspaceId, projectName, branchName);
                log.info("💻 만능 CLI 디버거 실행: {}", debugCmd);

                CreateContainerResponse container = dockerClient.createContainerCmd("ide-execution-env")
                        .withWorkingDir("/app")
                        .withTty(false).withStdinOpen(true)
                        .withCmd("sh", "-c", debugCmd)
                        .exec();

                String containerId = container.getId();
                runningContainerMap.put(sessionId, containerId);

                dockerClient.copyArchiveToContainerCmd(containerId).withHostResource(hostPath + "/.").withRemotePath("/app").exec();

                // 💡 [수정] 입력 파이프라인 제거.
                AttachContainerResultCallback callback = new AttachContainerResultCallback() {
                    @Override
                    public void onNext(Frame item) {
                        outputCallback.accept(new String(item.getPayload(), StandardCharsets.UTF_8));
                    }
                    @Override
                    public void onError(Throwable throwable) {
                        if (throwable.getMessage() != null && (throwable.getMessage().contains("Pipe closed") || throwable.getMessage().contains("Stream closed"))) return;
                        super.onError(throwable);
                    }
                };

                dockerClient.attachContainerCmd(containerId)
                        .withStdOut(true).withStdErr(true).withFollowStream(true).exec(callback);

                dockerClient.startContainerCmd(containerId).exec();
                dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitCompletion();

            } catch (Exception e) {
                log.error("CLI Debug Error", e);
            } finally {
                stopContainer(sessionId);
            }
        }).start();
    }
}