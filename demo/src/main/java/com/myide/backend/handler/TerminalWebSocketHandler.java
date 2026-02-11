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
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.get("type").asText();

            if ("START".equals(type) || "INIT".equals(type)) {
                String workspaceId = json.get("workspaceId").asText();
                String projectName = json.get("projectName").asText(); // [New]
                String branchName = json.has("branchName") ? json.get("branchName").asText() : "main-repo"; // [New]

                // DockerService 호출
                dockerService.createTerminal(session, workspaceId, projectName, branchName);
            }
            else if ("INPUT".equals(type)) {
                String command = json.get("command").asText();
                dockerService.writeToTerminal(session.getId(), command);
            }
        } catch (Exception e) {
            log.error("Terminal Error", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        dockerService.closeTerminal(session.getId());
    }
}