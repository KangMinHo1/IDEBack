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

// ... (패키지 및 import 생략) ...
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

            // 터미널 창을 처음 열었거나 초기화할 때
            if ("START".equals(type) || "INIT".equals(type)) {
                String workspaceId = json.get("workspaceId").asText();
                String projectName = json.get("projectName").asText();
                String branchName = json.has("branchName") ? json.get("branchName").asText() : "main-repo";

                // 도커를 이용해 가짜 리눅스 터미널(bash) 환경을 하나 만들어줍니다.
                dockerService.createTerminal(session, workspaceId, projectName, branchName);
            }
            // 터미널에 명령어(예: ls, cd 등)를 입력했을 때
            else if ("INPUT".equals(type)) {
                String command = json.get("command").asText();
                dockerService.writeToTerminal(session.getId(), command); // 도커 터미널 안으로 명령어를 쏴줍니다.
            }
        } catch (Exception e) {
            log.error("Terminal Error", e); // 에러가 나면 원인을 찾기 쉽게 빨간 로그를 남깁니다.
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        dockerService.closeTerminal(session.getId()); // 창이 닫히면 도커 터미널도 종료!
    }
}