package com.myide.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
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
public class PythonDebugStrategy implements DebugStrategy {

    private final ObjectMapper objectMapper;
    private final DockerService dockerService;
    private final Map<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();

    private final Map<String, Boolean> initDoneMap = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> bpMap = new ConcurrentHashMap<>();

    @Override
    public boolean supports(LanguageType language) { return language == LanguageType.PYTHON; }

    @Override
    public void startDebug(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, List<Map<String, Object>> breakpoints) {
        String sessionId = session.getId();
        webSocketSessions.put(sessionId, session);
        initDoneMap.put(sessionId, false);
        bpMap.put(sessionId, breakpoints);

        dockerService.debugPython(session, workspaceId, projectName, branchName, filePath, (output) -> {
            handlePdbOutput(sessionId, output);
        });
        log.info("🐍 [Python PDB] Debugger Attached: {}", sessionId);
    }

    private void handlePdbOutput(String sessionId, String output) {
        WebSocketSession session = webSocketSessions.get(sessionId);
        if (session == null || !session.isOpen()) return;

        if (output.contains("The program finished") || output.contains("End of file")) {
            sendOutput(session, createMessage("OUTPUT", "\n[System] 파이썬 디버깅이 완료되었습니다.\n"));
            stopDebug(sessionId);
            return;
        }

        // 💡 [핵심 1] 화면에 띄울 순수 출력문만 필터링 (TMI 제거)
        String[] lines = output.split("\n");
        StringBuilder cleanOutput = new StringBuilder();
        for (String l : lines) {
            String trimmed = l.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.startsWith("> /app/") && !trimmed.startsWith("-> ") && !trimmed.startsWith("(Pdb)") && !trimmed.contains("Breakpoint") && !trimmed.contains("End of file") && !trimmed.contains("The program finished")) {
                cleanOutput.append(l).append("\n");
            }
        }
        if (cleanOutput.length() > 0) {
            sendOutput(session, createMessage("OUTPUT", cleanOutput.toString()));
        }

        int state = initDoneMap.getOrDefault(sessionId, false) ? 1 : 0;

        // 💡 [핵심 2] 파이썬 디버거가 준비완료 "(Pdb)" 상태일 때 중단점 세팅!
        if (output.contains("(Pdb)") && state == 0) {
            initDoneMap.put(sessionId, true);
            List<Map<String, Object>> breakpoints = bpMap.getOrDefault(sessionId, List.of());

            new Thread(() -> {
                try {
                    Thread.sleep(300); // 파이썬 디버거가 정신을 차릴 여유
                    for (Map<String, Object> bp : breakpoints) {
                        int line = (Integer) bp.get("line");
                        if (line > 1) { // 1번 줄은 무조건 멈추니 생략
                            input(sessionId, "b " + line);
                            Thread.sleep(150);
                        }
                    }

                    // 1번 줄에 노란색 형광펜 띄우기!
                    Map<String, Object> suspendData = new HashMap<>();
                    suspendData.put("type", "SUSPENDED");
                    suspendData.put("line", 1);
                    suspendData.put("path", "main.py");
                    suspendData.put("variables", new HashMap<>());
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(suspendData)));
                } catch (Exception e) {}
            }).start();
            return;
        }

        // 💡 [핵심 3] 중간에 코드가 멈췄을 때 노란 줄 띄우기!
        if (state == 1) {
            Pattern suspendPattern = Pattern.compile(">\\s+(.*?)\\(([0-9]+)\\)");
            Matcher matcher = suspendPattern.matcher(output);

            if (matcher.find()) {
                String path = matcher.group(1);
                int lineNumber = Integer.parseInt(matcher.group(2));

                log.info("🎯 [Python] Suspended at Line {}. Path: {}", lineNumber, path);
                Map<String, Object> suspendData = new HashMap<>();
                suspendData.put("type", "SUSPENDED");
                suspendData.put("line", lineNumber);
                suspendData.put("path", path.replace("/app/", ""));
                suspendData.put("variables", new HashMap<>());
                try { session.sendMessage(new TextMessage(objectMapper.writeValueAsString(suspendData))); } catch (Exception e) {}
            }
        }
    }

    @Override
    public void handleCommand(String sessionId, String command) {
        switch (command) {
            case "STEP_OVER": input(sessionId, "n"); break;
            case "STEP_INTO": input(sessionId, "s"); break;
            case "CONTINUE":  input(sessionId, "c"); break;
            case "STOP":      input(sessionId, "q"); stopDebug(sessionId); break;
        }
    }

    @Override
    public void input(String sessionId, String input) {
        dockerService.writeToProcess(sessionId, input); // 여기서 \n 은 안 붙이는 게 맞습니다! (DockerService가 처리함)
    }

    @Override
    public void stopDebug(String sessionId) {
        WebSocketSession session = webSocketSessions.remove(sessionId);
        initDoneMap.remove(sessionId);
        bpMap.remove(sessionId);
        dockerService.stopContainer(sessionId);
        if (session != null) {
            try { if (session.isOpen()) session.close(); } catch (Exception e) {}
        }
    }

    private void sendOutput(WebSocketSession session, String message) { try { if (session != null && session.isOpen()) session.sendMessage(new TextMessage(message)); } catch (Exception e) {} }
    private String createMessage(String type, String data) { try { Map<String, String> map = new HashMap<>(); map.put("type", type); map.put("data", data); return objectMapper.writeValueAsString(map); } catch (Exception e) { return "{}"; } }
}