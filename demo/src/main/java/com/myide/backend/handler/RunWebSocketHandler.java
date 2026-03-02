package com.myide.backend.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
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
public class RunWebSocketHandler extends TextWebSocketHandler {

    private final DockerService dockerService;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService; // 💡 공통 서비스 주입

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
        String projectName = json.get("projectName").asText();
        String branchName = json.has("branchName") ? json.get("branchName").asText() : "master";
        String filePath = json.has("filePath") ? json.get("filePath").asText() : "";
        LanguageType language = LanguageType.valueOf(json.get("language").asText().toUpperCase());

        // 💡 [실무 방식] 실행 전 폴더 검증!
        Path checkPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
        if (!checkPath.toFile().exists()) {
            throw new RuntimeException("실행할 프로젝트 경로를 찾을 수 없습니다: " + checkPath.toString());
        }

        log.info("🚀 Run Request: Proj={}, Branch={}", projectName, branchName);
        dockerService.runProject(session, workspaceId, projectName, branchName, filePath, language);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        dockerService.stopContainer(session.getId());
    }
}