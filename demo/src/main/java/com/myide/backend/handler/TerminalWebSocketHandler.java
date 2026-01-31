package com.myide.backend.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.service.DockerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final DockerService dockerService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("💻 Terminal Connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            // 1. JSON 파싱
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.get("type").asText();

            if ("START".equals(type) || "INIT".equals(type)) { // Terminal.jsx가 INIT이나 START를 보낼 수 있음
                String workspaceId = json.has("workspaceId") ? json.get("workspaceId").asText() : json.get("projectName").asText();
                dockerService.createTerminal(session, workspaceId);
            }
            else if ("INPUT".equals(type)) {
                // 2. 명령어 추출 (JSON 껍데기 벗기기)
                String command = json.get("command").asText();
                // 3. 실제 명령어만 도커로 전송
                dockerService.writeToTerminal(session.getId(), command);
            }
        } catch (Exception e) {
            log.error("Terminal JSON Parse Error: {}", e.getMessage());
            // 파싱 실패 시, 쉘에 쓰레기 값을 입력하지 않고 무시함
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        dockerService.closeTerminal(session.getId());
        log.info("💻 Terminal Closed: {}", session.getId());
    }
}