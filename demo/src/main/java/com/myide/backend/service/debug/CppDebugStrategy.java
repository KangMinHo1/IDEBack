package com.myide.backend.service.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
import com.myide.backend.service.DockerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CppDebugStrategy implements DebugStrategy {

    private final ObjectMapper objectMapper;
    private final DockerService dockerService;
    private final Map<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();

    // C언어와 C++ 두 가지 언어 모두 이 클래스가 담당하겠다고 선언합니다. (다형성!)
    @Override
    public boolean supports(LanguageType language) {
        return language == LanguageType.C || language == LanguageType.CPP;
    }

    @Override
    public void startDebug(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, List<Map<String, Object>> breakpoints) {
        String sessionId = session.getId();
        webSocketSessions.put(sessionId, session);

        // 1. 디버깅을 하려면 반드시 '-g' 옵션을 넣어서 컴파일을 먼저 해야 합니다.
        // C와 C++을 구분해서 명령어를 다르게 세팅합니다.
        String buildCmd = filePath.endsWith(".c") ? "gcc -g -o main *.c" : "g++ -g -o main *.cpp";
        // 컴파일을 먼저 하고(&&), 조용히(-q) gdb 디버거를 켭니다.
        String fullCmd = buildCmd + " && gdb -q ./main";

        // 방금 만든 만능 CLI 메서드 호출!
        dockerService.debugWithCli(session, workspaceId, projectName, branchName, fullCmd, (output) -> {
            handleGdbOutput(sessionId, output);
        });

        // gdb가 켜질 때까지 약간 기다려줍니다.
        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        // 2. 프론트엔드에서 보낸 중단점들을 세팅합니다. (명령어: b 파일명:줄번호)
        String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf("/") + 1) : filePath;
        if (fileName.isEmpty()) fileName = filePath.endsWith(".c") ? "main.c" : "main.cpp";

        for (Map<String, Object> bp : breakpoints) {
            int line = (Integer) bp.get("line");
            input(sessionId, "b " + fileName + ":" + line); // 예: b main.cpp:5
        }

        // 3. 실행!
        input(sessionId, "run");
    }

    // GDB의 출력을 가로채서 현재 멈춘 줄 번호를 알아내는 로직입니다.
    private void handleGdbOutput(String sessionId, String output) {
        WebSocketSession session = webSocketSessions.get(sessionId);
        if (session == null || !session.isOpen()) return;

        sendOutput(session, createMessage("OUTPUT", output));

        // GDB는 멈췄을 때 "main.cpp:5" 또는 "at main.cpp:5" 형태로 출력합니다. 정규식으로 뽑아냅니다.
        Pattern suspendPattern = Pattern.compile("([a-zA-Z0-9_.-]+):([0-9]+)");
        Matcher matcher = suspendPattern.matcher(output);

        if (matcher.find()) {
            String path = matcher.group(1);
            int lineNumber = Integer.parseInt(matcher.group(2));

            log.info("🎯 [C/C++] Suspended at Line {}. Path: {}", lineNumber, path);

            Map<String, Object> suspendData = new HashMap<>();
            suspendData.put("type", "SUSPENDED");
            suspendData.put("line", lineNumber);
            suspendData.put("path", path);
            suspendData.put("variables", new HashMap<>());

            try { session.sendMessage(new TextMessage(objectMapper.writeValueAsString(suspendData))); } catch (Exception e) {}
        }
    }

    @Override
    public void handleCommand(String sessionId, String command) {
        // GDB 명령어로 치환
        switch (command) {
            case "STEP_OVER": input(sessionId, "n"); break; // next
            case "STEP_INTO": input(sessionId, "s"); break; // step
            case "CONTINUE":  input(sessionId, "c"); break; // continue
            case "STOP":
                input(sessionId, "q"); // quit
                input(sessionId, "y"); // "정말 끌 거냐?" 물어보면 yes
                stopDebug(sessionId);
                break;
        }
    }

    @Override
    public void input(String sessionId, String input) {
        dockerService.writeToProcess(sessionId, input + "\n");
    }

    @Override
    public void stopDebug(String sessionId) {
        WebSocketSession session = webSocketSessions.remove(sessionId);
        dockerService.stopContainer(sessionId);
        if (session != null) {
            sendOutput(session, createMessage("OUTPUT", "\n--- C/C++ Debugging Finished ---\n"));
            try { if (session.isOpen()) session.close(); } catch (Exception e) {}
        }
    }

    private void sendOutput(WebSocketSession session, String message) {
        try { if (session != null && session.isOpen()) session.sendMessage(new TextMessage(message)); } catch (Exception e) {}
    }
    private String createMessage(String type, String data) {
        try {
            Map<String, String> map = new HashMap<>(); map.put("type", type); map.put("data", data);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) { return "{}"; }
    }
}