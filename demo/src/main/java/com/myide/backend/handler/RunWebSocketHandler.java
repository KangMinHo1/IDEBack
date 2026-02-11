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

        // [수정] 필수 파라미터 3종 세트 수신
        String workspaceId = json.get("workspaceId").asText();
        String projectName = json.get("projectName").asText(); // 필수
        String branchName = json.has("branchName") ? json.get("branchName").asText() : "main-repo"; // 선택

        String filePath = json.has("filePath") ? json.get("filePath").asText() : "";
        LanguageType language = LanguageType.valueOf(json.get("language").asText().toUpperCase());

        log.info("🚀 Run Request: Proj={}, Branch={}", projectName, branchName);

        // DockerService 호출 시 파라미터 추가됨
        dockerService.runProject(session, workspaceId, projectName, branchName, filePath, language);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        dockerService.stopContainer(session.getId());
    }
}