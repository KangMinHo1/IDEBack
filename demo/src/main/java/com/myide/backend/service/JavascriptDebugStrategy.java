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
public class JavascriptDebugStrategy implements DebugStrategy {

    private final ObjectMapper objectMapper;
    private final DockerService dockerService;
    private final Map<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();

    private final Map<String, Integer> setupStateMap = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> bpMap = new ConcurrentHashMap<>();

    @Override
    public boolean supports(LanguageType language) { return language == LanguageType.JAVASCRIPT; }

    @Override
    public void startDebug(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, List<Map<String, Object>> breakpoints) {
        String sessionId = session.getId();
        webSocketSessions.put(sessionId, session);
        setupStateMap.put(sessionId, 0);
        bpMap.put(sessionId, breakpoints);

        String tempFileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf("/") + 1) : filePath;
        final String fileName = tempFileName.isEmpty() ? "index.js" : tempFileName;

        String debugCmd = "node inspect " + fileName;
        log.info("💻 [JS] 디버거 실행: {}", debugCmd);

        dockerService.debugWithCli(session, workspaceId, projectName, branchName, debugCmd, (output) -> {
            handleNodeOutput(sessionId, output, fileName);
        });
    }

    private void handleNodeOutput(String sessionId, String output, String fileName) {
        WebSocketSession session = webSocketSessions.get(sessionId);
        if (session == null || !session.isOpen()) return;

        // 💡 [핵심 1] 화면에 뜨는 지저분한 로그, 에러, ^ 기호 등을 전부 필터링합니다.
        String[] lines = output.split("\n");
        StringBuilder cleanOutput = new StringBuilder();

        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty() || t.equals("^")) continue;

            if (t.startsWith("< Debugger") || t.startsWith("< For help") ||
                    t.startsWith("connecting to") || t.startsWith("ok") ||
                    t.contains("Warning: script") || t.contains("was not loaded yet") ||
                    t.contains("ERR_DEBUGGER_ERROR") || t.contains("Can only perform operation while paused") ||
                    t.contains("node:internal") || t.contains("node:events") || t.contains("node:domain") ||
                    t.contains("code: -32000") || t.startsWith("at Socket.emit") || t.startsWith("at ") ||
                    t.equals("}") || t.equals("<") || t.startsWith("debug>") || t.startsWith("break in") ||
                    t.startsWith("Break on start") || t.matches("^[>\\s0-9]+.*")) {

                // 진짜 프로그램 출력문(보통 "< " 뒤에 옴)만 골라냅니다.
                if (t.startsWith("< ")) {
                    String realOutput = t.substring(2);
                    if (!realOutput.contains("Waiting for the debugger")) {
                        cleanOutput.append(realOutput).append("\n");
                    }
                }
            } else {
                cleanOutput.append(t).append("\n");
            }
        }

        if (cleanOutput.length() > 0) {
            sendOutput(session, createMessage("OUTPUT", cleanOutput.toString()));
        }

        // 프로그램 완전 종료 감지
        if (output.contains("Waiting for the debugger to disconnect") || output.contains("프로그램이 끝났습니다")) {
            sendOutput(session, createMessage("OUTPUT", "\n[System] JS 디버깅이 완료되었습니다.\n"));
            stopDebug(sessionId);
            return;
        }

        int state = setupStateMap.getOrDefault(sessionId, 0);

        // 💡 [핵심 2] Node.js가 완전히 멈췄을 때만 중단점 세팅 (여유 시간 부여)
        if (output.contains("debug>") && state == 0) {
            setupStateMap.put(sessionId, 1);
            List<Map<String, Object>> breakpoints = bpMap.getOrDefault(sessionId, List.of());

            new Thread(() -> {
                try {
                    Thread.sleep(500); // 디버거가 정신차릴 시간을 충분히 줍니다.
                    for (Map<String, Object> bp : breakpoints) {
                        int line = (Integer) bp.get("line");
                        if (line > 1) {
                            // 안전하게 파일명까지 명시하여 중단점 세팅
                            input(sessionId, "setBreakpoint('" + fileName + "', " + line + ")");
                            Thread.sleep(200);
                        }
                    }
                    setupStateMap.put(sessionId, 2);

                    // 1번 줄에서 멈췄다고 화면에 보고
                    Map<String, Object> suspendData = new HashMap<>();
                    suspendData.put("type", "SUSPENDED");
                    suspendData.put("line", 1);
                    suspendData.put("path", fileName);
                    suspendData.put("variables", new HashMap<>());
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(suspendData)));
                } catch (Exception e) {}
            }).start();
            return;
        }

        // 💡 [핵심 3] 특정 줄에서 멈췄을 때 (사용자 코드 vs 시스템 코드)
        if (state == 2) {
            Pattern suspendPattern = Pattern.compile("(?i)(?:break on start in|break in) (.*?):([0-9]+)");
            Matcher matcher = suspendPattern.matcher(output);

            if (matcher.find()) {
                String path = matcher.group(1);
                int lineNumber = Integer.parseInt(matcher.group(2));

                // 내 코드(index.js)가 아니면 묻지도 따지지도 않고 끝까지 실행(c) 해버립니다!
                if (!path.equals(fileName) && !path.contains(fileName)) {
                    log.info("🎯 [JS] 시스템 코드로 진입. 나머지 코드를 한 번에 실행(c)합니다.");
                    input(sessionId, "c");
                    return;
                }

                // 내 코드 안에서 멈췄을 때만 노란 줄 표시
                log.info("🎯 [JS] Suspended at Line {}. Path: {}", lineNumber, path);
                Map<String, Object> suspendData = new HashMap<>();
                suspendData.put("type", "SUSPENDED");
                suspendData.put("line", lineNumber);
                suspendData.put("path", path);
                suspendData.put("variables", new HashMap<>());
                try { session.sendMessage(new TextMessage(objectMapper.writeValueAsString(suspendData))); } catch (Exception e) {}
            }
        }
    }

    @Override
    public void handleCommand(String sessionId, String command) {
        // 💡 [핵심 4] 오류의 주범이었던 긴 명령어를 짧은 네이티브 명령어로 변경!
        switch (command) {
            case "STEP_OVER": input(sessionId, "n"); break; // next 대신 n
            case "STEP_INTO": input(sessionId, "s"); break; // step 대신 s
            case "CONTINUE":  input(sessionId, "c"); break; // cont 대신 c
            case "STOP":      input(sessionId, ".exit"); stopDebug(sessionId); break;
        }
    }

    @Override
    public void input(String sessionId, String input) {
        dockerService.writeToProcess(sessionId, input);
    }

    @Override
    public void stopDebug(String sessionId) {
        WebSocketSession session = webSocketSessions.remove(sessionId);
        setupStateMap.remove(sessionId);
        bpMap.remove(sessionId);
        dockerService.stopContainer(sessionId);
        if (session != null) {
            try { if (session.isOpen()) session.close(); } catch (Exception e) {}
        }
    }

    private void sendOutput(WebSocketSession session, String message) { try { if (session != null && session.isOpen()) session.sendMessage(new TextMessage(message)); } catch (Exception e) {} }
    private String createMessage(String type, String data) { try { Map<String, String> map = new HashMap<>(); map.put("type", type); map.put("data", data); return objectMapper.writeValueAsString(map); } catch (Exception e) { return "{}"; } }
}