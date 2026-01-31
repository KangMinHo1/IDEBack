package com.myide.backend.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
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
public class RunWebSocketHandler extends TextWebSocketHandler {

    private final DockerService dockerService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("🚀 Run Session Connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode json = objectMapper.readTree(message.getPayload());

        String type = json.has("type") ? json.get("type").asText() : "RUN";

        if ("STOP".equals(type)) {
            dockerService.stopContainer(session.getId());
            return;
        }

        if ("INPUT".equals(type)) {
            String input = json.get("input").asText();
            dockerService.writeToProcess(session.getId(), input);
            return;
        }

        String workspaceId = json.get("workspaceId").asText();
        // [수정] 파일 경로 받기
        String filePath = json.has("filePath") ? json.get("filePath").asText() : "";
        String languageStr = json.get("language").asText();
        LanguageType language = LanguageType.valueOf(languageStr.toUpperCase());

        log.info("🚀 실행 요청: WS={}, Path={}, Lang={}", workspaceId, filePath, language);
        // [수정] filePath 전달
        dockerService.runProject(session, workspaceId, filePath, language);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        dockerService.stopContainer(session.getId());
    }
}