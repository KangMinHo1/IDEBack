package com.myide.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DebugService {

    private final ObjectMapper objectMapper;
    private final DockerService dockerService;

    private final Map<String, VirtualMachine> debugSessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();

    public void startDebug(WebSocketSession session, String userId, String projectName, List<Map<String, Object>> breakpoints) {
        String sessionId = session.getId();
        webSocketSessions.put(sessionId, session);

        new Thread(() -> {
            try {
                // 1. 도커 실행 및 포트 획득 (int 반환)
                int assignedPort = dockerService.debugProject(session, userId, projectName, com.myide.backend.domain.LanguageType.JAVA, (logText) -> {
                    sendOutput(session, createMessage("OUTPUT", logText));
                });

                log.info("🎯 디버거 접속 시도: localhost:{}", assignedPort);
                sendOutput(session, createMessage("OUTPUT", "[System] Waiting for JVM on port " + assignedPort + "..."));

                // 2. JDI 연결 (획득한 포트로 접속)
                VirtualMachine vm = attachToRemoteJVM(sessionId, "localhost", String.valueOf(assignedPort));
                debugSessions.put(sessionId, vm);

                log.info("🚀 [JDI] Attached to JVM: {}", sessionId);
                sendOutput(session, createMessage("OUTPUT", "[System] Debugger Attached."));

                // 3. Main 클래스 로딩 감지
                EventRequestManager erm = vm.eventRequestManager();
                ClassPrepareRequest cpr = erm.createClassPrepareRequest();
                cpr.addClassFilter("Main");
                cpr.enable();

                // 4. 시작
                vm.resume();
                processEvents(vm, session, sessionId, breakpoints);

            } catch (Exception e) {
                log.error("디버깅 시작 실패", e);
                sendOutput(session, createMessage("ERROR", "Debugger Error: " + e.getMessage()));
                stopDebug(sessionId);
            }
        }).start();
    }

    // [추가] 입력 전달
    public void input(String sessionId, String input) {
        dockerService.writeToProcess(sessionId, input);
    }

    private void processEvents(VirtualMachine vm, WebSocketSession session, String sessionId, List<Map<String, Object>> breakpoints) {
        EventQueue eventQueue = vm.eventQueue();
        boolean running = true;

        while (running) {
            try {
                EventSet eventSet = eventQueue.remove();
                for (Event event : eventSet) {
                    if (event instanceof ClassPrepareEvent) {
                        createBreakpoints(vm, (ClassPrepareEvent) event, breakpoints);
                        vm.resume();
                    }
                    else if (event instanceof BreakpointEvent || event instanceof StepEvent) {
                        LocatableEvent locatable = (LocatableEvent) event;
                        int lineNumber = locatable.location().lineNumber();

                        String sourcePath;
                        try { sourcePath = locatable.location().sourceName(); } catch (Exception e) { sourcePath = "Main.java"; }

                        log.info("🎯 Suspended at Line {}. Path: {}", lineNumber, sourcePath);

                        Map<String, Object> suspendData = new HashMap<>();
                        suspendData.put("type", "SUSPENDED");
                        suspendData.put("line", lineNumber);
                        suspendData.put("path", sourcePath);
                        suspendData.put("variables", getVariables(locatable.thread()));

                        sendOutput(session, objectMapper.writeValueAsString(suspendData));
                    }
                    else if (event instanceof VMDisconnectEvent || event instanceof VMDeathEvent) {
                        running = false;
                        stopDebug(sessionId);
                    }
                }
            } catch (Exception e) { running = false; }
        }
    }

    private void createBreakpoints(VirtualMachine vm, ClassPrepareEvent event, List<Map<String, Object>> breakpoints) {
        ReferenceType refType = event.referenceType();
        EventRequestManager erm = vm.eventRequestManager();

        for (Map<String, Object> bp : breakpoints) {
            int line = (Integer) bp.get("line");
            try {
                List<Location> locations = refType.locationsOfLine(line);
                if (!locations.isEmpty()) {
                    BreakpointRequest bpReq = erm.createBreakpointRequest(locations.get(0));
                    bpReq.enable();
                }
            } catch (Exception e) {}
        }
    }

    public void handleCommand(String sessionId, String command) {
        VirtualMachine vm = debugSessions.get(sessionId);
        if (vm == null) return;
        try {
            EventRequestManager erm = vm.eventRequestManager();
            erm.deleteEventRequests(erm.stepRequests());

            switch (command) {
                case "STEP_OVER":
                    vm.allThreads().stream().filter(t -> t.name().equals("main")).findFirst().ifPresent(thread -> {
                        StepRequest step = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
                        step.addCountFilter(1);
                        step.addClassExclusionFilter("java.*");
                        step.addClassExclusionFilter("javax.*");
                        step.addClassExclusionFilter("sun.*");
                        step.enable();
                    });
                    vm.resume();
                    break;
                case "STEP_INTO":
                    vm.allThreads().stream().filter(t -> t.name().equals("main")).findFirst().ifPresent(thread -> {
                        StepRequest step = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
                        step.addCountFilter(1);
                        step.addClassExclusionFilter("java.*");
                        step.addClassExclusionFilter("javax.*");
                        step.addClassExclusionFilter("sun.*");
                        step.enable();
                    });
                    vm.resume();
                    break;
                case "CONTINUE":
                    vm.resume();
                    break;
                case "STOP":
                    stopDebug(sessionId);
                    break;
            }
        } catch (Exception e) {}
    }

    public void stopDebug(String sessionId) {
        WebSocketSession session = webSocketSessions.get(sessionId);
        VirtualMachine vm = debugSessions.get(sessionId);
        if (vm != null) {
            try { vm.dispose(); } catch (Exception e) {}
            debugSessions.remove(sessionId);
        }
        dockerService.stopContainer(sessionId);
        if (session != null) {
            sendOutput(session, createMessage("OUTPUT", "\n--- Debugging Finished ---\n"));
            try { if (session.isOpen()) session.close(); } catch (Exception e) {}
            webSocketSessions.remove(sessionId);
            log.info("🐛 Debug Session Closed: {}", sessionId);
        }
    }

    private Map<String, String> getVariables(ThreadReference thread) {
        Map<String, String> variables = new HashMap<>();
        try {
            if (thread.frameCount() > 0) {
                StackFrame frame = thread.frame(0);
                List<LocalVariable> visibleVariables = frame.visibleVariables();
                for (LocalVariable var : visibleVariables) {
                    Value value = frame.getValue(var);
                    variables.put(var.name(), value != null ? value.toString() : "null");
                }
            }
        } catch (Exception e) {}
        return variables;
    }

    private VirtualMachine attachToRemoteJVM(String sessionId, String host, String port) throws Exception {
        VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        AttachingConnector conn = vmm.attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("SocketAttach connector not found"));
        Map<String, Connector.Argument> params = conn.defaultArguments();
        params.get("hostname").setValue(host);
        params.get("port").setValue(port); // [수정] 동적 포트 연결
        for(int i=0; i<60; i++) {
            if (!dockerService.isContainerAlive(sessionId)) throw new RuntimeException("Container died");
            try { return conn.attach(params); } catch(IOException e) { Thread.sleep(1000); }
        }
        throw new RuntimeException("Debugger connect timeout");
    }

    private void sendOutput(WebSocketSession session, String message) {
        try { if (session != null && session.isOpen()) session.sendMessage(new TextMessage(message)); } catch (Exception e) {}
    }

    private String createMessage(String type, String data) {
        try {
            Map<String, String> map = new HashMap<>();
            map.put("type", type);
            map.put("data", data);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) { return "{}"; }
    }
}