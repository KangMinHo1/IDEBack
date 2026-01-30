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
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("🐛 Debug Session Connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode json = objectMapper.readTree(message.getPayload());
        String type = json.get("type").asText();

        if ("START".equals(type)) {
            String userId = json.get("userId").asText();
            String projectName = json.get("projectName").asText();
            JsonNode breakpointsNode = json.get("breakpoints");

            List<Map<String, Object>> breakpoints = objectMapper.convertValue(
                    breakpointsNode,
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            debugService.startDebug(session, userId, projectName, breakpoints);

        } else if ("STOP".equals(type)) {
            debugService.stopDebug(session.getId());

        } else if ("INPUT".equals(type)) { // [추가] 입력 처리
            String input = json.get("input").asText();
            debugService.input(session.getId(), input);

        } else {
            debugService.handleCommand(session.getId(), type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("🐛 Debug Session Closed: {}", session.getId());
        debugService.stopDebug(session.getId());
    }
}