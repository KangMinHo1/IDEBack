package com.myide.backend.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.service.DockerService;
import com.myide.backend.service.WorkspaceService; // 💡 추가됨
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final DockerService dockerService;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService; // 💡 공통 서비스 주입

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.get("type").asText();

            if ("START".equals(type) || "INIT".equals(type)) {
                String workspaceId = json.get("workspaceId").asText();
                String projectName = json.get("projectName").asText();
                String branchName = json.has("branchName") ? json.get("branchName").asText() : "master";

                // 💡 [실무 방식] 실행 전 폴더 검증!
                Path checkPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
                if (!checkPath.toFile().exists()) {
                    throw new RuntimeException("터미널을 열 프로젝트 경로를 찾을 수 없습니다: " + checkPath.toString());
                }

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