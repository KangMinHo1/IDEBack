package com.myide.backend.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.service.DebugService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DebugWebSocketHandler extends TextWebSocketHandler {

    private final DebugService debugService;
    private final ObjectMapper objectMapper;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode json = objectMapper.readTree(message.getPayload());
        if (!json.has("type")) return;
        String type = json.get("type").asText();

        if ("START".equals(type)) {
            String workspaceId = json.get("workspaceId").asText();
            String projectName = json.get("projectName").asText(); // [New]
            String branchName = json.has("branchName") ? json.get("branchName").asText() : "main-repo"; // [New]
            String filePath = json.has("filePath") ? json.get("filePath").asText() : "";

            JsonNode breakpointsNode = json.get("breakpoints");
            List<Map<String, Object>> breakpoints = objectMapper.convertValue(
                    breakpointsNode,
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            // DebugService에도 projectName, branchName 전달 필요!
            // (DebugService의 startDebug 메서드 시그니처도 수정해야 합니다 -> 아래 설명 참고)
            debugService.startDebug(session, workspaceId, projectName, branchName, filePath, breakpoints);

        } else if ("STOP".equals(type)) {
            debugService.stopDebug(session.getId());
        } else if ("INPUT".equals(type)) {
            String input = json.get("input").asText();
            debugService.input(session.getId(), input);
        } else {
            debugService.handleCommand(session.getId(), type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        debugService.stopDebug(session.getId());
    }
}