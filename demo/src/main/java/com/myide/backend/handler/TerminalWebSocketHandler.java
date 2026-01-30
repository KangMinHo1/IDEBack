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
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 메시지 구조: { "type": "INIT" or "INPUT", "command": "ls -al", ... }
        JsonNode json = objectMapper.readTree(message.getPayload());
        String type = json.get("type").asText();

        if ("INIT".equals(type)) {
            // 터미널 초기화 요청
            String userId = json.get("userId").asText();
            String projectName = json.get("projectName").asText();
            dockerService.createTerminal(session, userId, projectName);

        } else if ("INPUT".equals(type)) {
            // 키보드 입력
            String command = json.get("command").asText();
            dockerService.writeToTerminal(session.getId(), command);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // 소켓 끊기면 터미널 컨테이너도 삭제
        dockerService.closeTerminal(session.getId());
    }
}