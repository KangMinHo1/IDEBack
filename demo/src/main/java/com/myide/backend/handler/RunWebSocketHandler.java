package com.myide.backend.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.ProjectRequest;
import com.myide.backend.service.DockerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus; // 추가
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

        // 1. 입력 메시지 (INPUT)
        if (json.has("type") && "INPUT".equals(json.get("type").asText())) {
            String inputData = json.get("input").asText();
            dockerService.writeToProcess(session.getId(), inputData);
            return;
        }

        // [신규] 2. 중단 요청 (STOP)
        if (json.has("type") && "STOP".equals(json.get("type").asText())) {
            log.info("실행 중단 요청 수신: {}", session.getId());
            dockerService.stopContainer(session.getId()); // 컨테이너 강제 종료
            return;
        }

        // 3. 실행 요청 (RUN)
        ProjectRequest request = objectMapper.treeToValue(json, ProjectRequest.class);
        log.info("실행 요청 수신: {}", request.getProjectName());
        dockerService.runProject(session, request.getUserId(), request.getProjectName(), request.getLanguage());
    }

    // 소켓 끊기면 컨테이너도 같이 정리
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        dockerService.stopContainer(session.getId());
    }
}