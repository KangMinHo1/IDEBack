package com.myide.backend.service.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.service.DockerService;
import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import com.myide.backend.domain.LanguageType;
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
@Service // 이 클래스는 서비스 로직을 담당하는 스프링 빈입니다.
@RequiredArgsConstructor
public class JavaDebugStrategy implements DebugStrategy {

    private final ObjectMapper objectMapper;
    private final DockerService dockerService;

    // 여러 명의 사용자가 동시에 디버깅을 할 수 있으니, 각 사용자의 '세션 아이디'를 키(Key)로 해서
    // 진행 중인 디버깅 가상 머신(VirtualMachine)과 웹소켓을 저장해두는 맵(Map)입니다.
    // ConcurrentHashMap은 여러 사용자가 동시에 접근해도 안전(Thread-safe)하게 만들어줍니다.
    private final Map<String, VirtualMachine> debugSessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();

    @Override
    public boolean supports(LanguageType language) {
        return language == LanguageType.JAVA;
    }

    // 디버깅 시작 메서드
    @Override
    public void startDebug(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, List<Map<String, Object>> breakpoints) {
        String sessionId = session.getId();
        webSocketSessions.put(sessionId, session); // 나중에 결과를 돌려주기 위해 웹소켓 세션을 저장해둡니다.

        // 디버깅은 시간이 오래 걸릴 수 있으니 메인 스레드가 멈추지 않게 새로운 스레드(작업자)를 하나 만듭니다.
        new Thread(() -> {
            try {
                // 1. 도커 컨테이너를 실행하여 그 안에서 자바 프로그램을 디버그 모드로 켭니다.
                // 포트 번호(assignedPort)를 받아옵니다.
                int assignedPort = dockerService.debugProject(session, workspaceId, projectName, branchName, filePath, LanguageType.JAVA, (logText) -> {
                    // 컨테이너에서 글자가 출력되면 바로 프론트엔드로 보내줍니다.
                    sendOutput(session, createMessage("OUTPUT", logText));
                });

                log.info("🎯 디버거 접속 시도: localhost:{}", assignedPort);
                sendOutput(session, createMessage("OUTPUT", "[System] Waiting for JVM on port " + assignedPort + "..."));

                // 2. 실행된 도커 컨테이너 안의 자바 프로그램(JVM)에 우리의 디버거를 연결(Attach)합니다.
                VirtualMachine vm = attachToRemoteJVM(sessionId, "localhost", String.valueOf(assignedPort));
                debugSessions.put(sessionId, vm); // 연결된 가상 머신(VM) 정보를 저장합니다.

                log.info("🚀 [JDI] Attached to JVM: {}", sessionId);
                sendOutput(session, createMessage("OUTPUT", "[System] Debugger Attached."));

                // 💡 프론트에서 받은 filePath에서 클래스 이름 추출
                String fileName = filePath;
                if (filePath != null && filePath.contains("/")) {
                    fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
                }
                String className = fileName.replace(".java", "");

                // 3. 동적으로 넘어온 클래스가 로딩될 때 감시 시작!
                EventRequestManager erm = vm.eventRequestManager();
                ClassPrepareRequest cpr = erm.createClassPrepareRequest();
                cpr.addClassFilter(className); // 💡 Main 대신 className을 넣습니다!
                cpr.enable();

                // 4. 자바 프로그램 실행을 재개(resume)하고, 발생하는 이벤트(줄 멈춤 등)를 계속 지켜보는 무한 루프를 돕니다.
                vm.resume();
                processEvents(vm, session, sessionId, breakpoints);

            } catch (Exception e) {
                // 도중에 에러가 나면 프론트엔드에 에러 메시지를 쏴주고 디버깅을 강제 종료합니다.
                log.error("디버깅 시작 실패", e);
                sendOutput(session, createMessage("ERROR", "Debugger Error: " + e.getMessage()));
                stopDebug(sessionId);
            }
        }).start(); // 만든 스레드를 실행!
    }

    @Override
    public void input(String sessionId, String input) {
        dockerService.writeToProcess(sessionId, input); // 콘솔 입력값 전달
    }

    // 가상 머신에서 일어나는 일(이벤트)들을 관찰하고 처리하는 곳입니다.
    private void processEvents(VirtualMachine vm, WebSocketSession session, String sessionId, List<Map<String, Object>> breakpoints) {
        EventQueue eventQueue = vm.eventQueue(); // 이벤트가 쌓이는 큐를 가져옵니다.
        boolean running = true;

        while (running) { // 디버깅이 끝날 때까지 무한 반복하며 지켜봅니다.
            try {
                // 큐에서 이벤트 뭉치를 꺼냅니다. (이 때 이벤트가 발생할 때까지 잠시 대기합니다)
                EventSet eventSet = eventQueue.remove();

                for (Event event : eventSet) {
                    // 클래스 준비가 완료되었다는 이벤트면? (아까 설정한 Main 클래스 로딩)
                    if (event instanceof ClassPrepareEvent) {
                        createBreakpoints(vm, (ClassPrepareEvent) event, breakpoints); // 프론트에서 받아온 줄 번호들에 중단점(빨간점)을 찍습니다.
                        vm.resume(); // 다시 실행!

                        // 중단점(Breakpoint)에 걸렸거나, 한 줄씩 실행(Step)하다가 멈춘 이벤트라면?
                    } else if (event instanceof BreakpointEvent || event instanceof StepEvent) {
                        LocatableEvent locatable = (LocatableEvent) event;
                        int lineNumber = locatable.location().lineNumber(); // 현재 멈춘 줄 번호를 가져옵니다.

                        String sourcePath;
                        try { sourcePath = locatable.location().sourceName(); } catch (Exception e) { sourcePath = "Main.java"; }

                        log.info("🎯 Suspended at Line {}. Path: {}", lineNumber, sourcePath);

                        // 프론트엔드로 보낼 "현재 상태" 데이터를 맵(Map)으로 포장합니다.
                        Map<String, Object> suspendData = new HashMap<>();
                        suspendData.put("type", "SUSPENDED");
                        suspendData.put("line", lineNumber);
                        suspendData.put("path", sourcePath);
                        suspendData.put("variables", getVariables(locatable.thread())); // 💡 현재 메모리에 있는 변수들의 이름과 값을 싹 다 읽어옵니다.

                        // 포장한 데이터를 프론트엔드로 슝~ 보냅니다.
                        sendOutput(session, objectMapper.writeValueAsString(suspendData));

                        // 자바 프로그램이 종료되었거나 연결이 끊어진 이벤트라면?
                    } else if (event instanceof VMDisconnectEvent || event instanceof VMDeathEvent) {
                        running = false; // 무한 루프 탈출
                        stopDebug(sessionId); // 디버깅 끄기
                    }
                }
            } catch (Exception e) { running = false; }
        }
    }

    // 사용자가 찍은 줄 번호(breakpoints)에 실제로 디버거 함정을 파놓는 작업입니다.
    private void createBreakpoints(VirtualMachine vm, ClassPrepareEvent event, List<Map<String, Object>> breakpoints) {
        ReferenceType refType = event.referenceType();
        EventRequestManager erm = vm.eventRequestManager();
        for (Map<String, Object> bp : breakpoints) {
            int line = (Integer) bp.get("line");
            try {
                List<Location> locations = refType.locationsOfLine(line); // 해당 줄 번호의 실제 코드 위치를 찾아서
                if (!locations.isEmpty()) {
                    BreakpointRequest bpReq = erm.createBreakpointRequest(locations.get(0)); // 브레이크 포인트를 걸어줍니다.
                    bpReq.enable();
                }
            } catch (Exception e) {}
        }
    }

    // 프론트에서 "다음 줄로 가!"(STEP_OVER) 등의 명령 버튼을 눌렀을 때 처리합니다.
    @Override
    public void handleCommand(String sessionId, String command) {
        VirtualMachine vm = debugSessions.get(sessionId);
        if (vm == null) return; // 연결된 VM이 없으면 무시

        try {
            EventRequestManager erm = vm.eventRequestManager();
            erm.deleteEventRequests(erm.stepRequests()); // 기존에 걸어둔 스텝 명령은 지워줍니다 (중복 방지).

            switch (command) {
                case "STEP_OVER": // 함수 안으로 안 들어가고 다음 줄로
                    requestStep(vm, erm, StepRequest.STEP_OVER);
                    break;
                case "STEP_INTO": // 함수가 있으면 함수 안으로 들어가기
                    requestStep(vm, erm, StepRequest.STEP_INTO);
                    break;
                case "CONTINUE": // 다음 중단점이 나올 때까지 쭈욱 실행
                    vm.resume();
                    break;
                case "STOP": // 디버깅 강제 종료
                    stopDebug(sessionId);
                    break;
            }
        } catch (Exception e) {
            log.error("Debug command failed", e);
        }
    }

    // 스텝(한 줄 실행) 요청을 세팅하는 헬퍼 메서드입니다.
    private void requestStep(VirtualMachine vm, EventRequestManager erm, int depth) {
        // 'main' 스레드를 찾아서
        vm.allThreads().stream().filter(t -> t.name().equals("main")).findFirst().ifPresent(thread -> {
            StepRequest step = erm.createStepRequest(thread, StepRequest.STEP_LINE, depth);
            step.addCountFilter(1); // 딱 1번만 실행하고 멈추게 설정
            // 자바 기본 라이브러리(java.lang.String 등) 소스코드 안쪽까지 디버깅하러 들어가는 건 너무 복잡하니 막아둡니다. (ExclusionFilter)
            step.addClassExclusionFilter("java.*");
            step.addClassExclusionFilter("javax.*");
            step.addClassExclusionFilter("sun.*");
            step.enable();
        });
        vm.resume(); // 세팅했으니 다시 진행!
    }

    // 디버깅을 완전 종료하는 메서드
    @Override
    public void stopDebug(String sessionId) {
        WebSocketSession session = webSocketSessions.get(sessionId);
        VirtualMachine vm = debugSessions.get(sessionId);

        if (vm != null) {
            try { vm.dispose(); } catch (Exception e) {} // 가상머신 연결 해제
            debugSessions.remove(sessionId); // 목록에서 삭제
        }
        dockerService.stopContainer(sessionId); // 도커 컨테이너도 끕니다.

        if (session != null) {
            sendOutput(session, createMessage("OUTPUT", "\n--- Debugging Finished ---\n"));
            try { if (session.isOpen()) session.close(); } catch (Exception e) {} // 웹소켓 연결 닫기
            webSocketSessions.remove(sessionId);
            log.info("🐛 Debug Session Closed: {}", sessionId);
        }
    }

    // 현재 실행이 멈춘 시점의 모든 지역 변수(Local Variable)의 이름과 값을 문자열로 추출합니다.
    private Map<String, String> getVariables(ThreadReference thread) {
        Map<String, String> variables = new HashMap<>();
        try {
            if (thread.frameCount() > 0) {
                StackFrame frame = thread.frame(0); // 현재 실행 중인 블록(프레임)
                List<LocalVariable> visibleVariables = frame.visibleVariables(); // 보이는 변수들 가져오기
                for (LocalVariable var : visibleVariables) {
                    Value value = frame.getValue(var); // 실제 메모리에 든 값 읽어오기
                    variables.put(var.name(), value != null ? value.toString() : "null");
                }
            }
        } catch (Exception e) {}
        return variables;
    }

    // 외부(도커 내부)에서 실행 중인 자바 프로그램에 JDI 소켓으로 원격 접속하는 복잡한 설정입니다.
    private VirtualMachine attachToRemoteJVM(String sessionId, String host, String port) throws Exception {
        VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        // 소켓 통신을 통해 원격으로 붙을 수 있는 커넥터를 찾습니다.
        AttachingConnector conn = vmm.attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("SocketAttach connector not found"));

        // 호스트와 포트번호(도커의 포트)를 세팅합니다.
        Map<String, Connector.Argument> params = conn.defaultArguments();
        params.get("hostname").setValue(host);
        params.get("port").setValue(port);

        // 도커 컨테이너가 뜨고 자바 프로그램이 준비될 때까지 시간이 걸릴 수 있으므로 최대 60번(60초) 재시도합니다.
        for(int i=0; i<60; i++) {
            if (!dockerService.isContainerAlive(sessionId)) throw new RuntimeException("Container died"); // 컨테이너가 죽었으면 즉시 포기
            try { return conn.attach(params); } catch(IOException e) { Thread.sleep(1000); } // 1초 대기 후 다시 시도
        }
        throw new RuntimeException("Debugger connect timeout");
    }

    // 프론트엔드로 웹소켓 메시지를 보내는 공통 도우미 메서드
    private void sendOutput(WebSocketSession session, String message) {
        try { if (session != null && session.isOpen()) session.sendMessage(new TextMessage(message)); } catch (Exception e) {}
    }

    // 타입과 데이터를 JSON 포맷의 문자열로 만들어주는 도우미 메서드
    private String createMessage(String type, String data) {
        try {
            Map<String, String> map = new HashMap<>();
            map.put("type", type);
            map.put("data", data);
            return objectMapper.writeValueAsString(map); // Map -> JSON String
        } catch (Exception e) { return "{}"; }
    }
}