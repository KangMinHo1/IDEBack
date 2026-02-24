package com.myide.backend.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.Project;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.service.debug.DebugStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DebugWebSocketHandler extends TextWebSocketHandler {

    private final List<DebugStrategy> debugStrategies;
    private final ObjectMapper objectMapper;
    private final ProjectRepository projectRepository;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 💡 [핵심 해결] 전체 로직을 try-catch로 감싸서 에러가 나도 웹소켓이 끊기지 않게 방어합니다.
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            if (!json.has("type")) return;
            String type = json.get("type").asText();

            if ("START".equals(type)) {
                String workspaceId = json.get("workspaceId").asText();
                String projectName = json.get("projectName").asText();
                String branchName = json.has("branchName") ? json.get("branchName").asText() : "main-repo";
                String filePath = json.has("filePath") ? json.get("filePath").asText() : "";

                JsonNode breakpointsNode = json.get("breakpoints");
                List<Map<String, Object>> breakpoints = objectMapper.convertValue(
                        breakpointsNode,
                        new TypeReference<List<Map<String, Object>>>() {}
                );

                Project project = projectRepository.findAll().stream()
                        .filter(p -> p.getWorkspace().getUuid().equals(workspaceId) && p.getName().equals(projectName))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

                LanguageType language = project.getLanguage();

                DebugStrategy strategy = debugStrategies.stream()
                        .filter(s -> s.supports(language))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException(language.name() + " 언어는 아직 디버깅을 지원하지 않습니다."));

                log.info("🎯 선택된 디버깅 전략: {}", strategy.getClass().getSimpleName());
                strategy.startDebug(session, workspaceId, projectName, branchName, filePath, breakpoints);

            } else if ("STOP".equals(type) || "INPUT".equals(type) || "STEP_OVER".equals(type) || "STEP_INTO".equals(type) || "CONTINUE".equals(type)) {
                String sessionId = session.getId();
                if ("STOP".equals(type)) {
                    debugStrategies.forEach(s -> s.stopDebug(sessionId));
                } else if ("INPUT".equals(type)) {
                    String input = json.get("input").asText();
                    debugStrategies.forEach(s -> s.input(sessionId, input));
                } else {
                    debugStrategies.forEach(s -> s.handleCommand(sessionId, type));
                }
            }
        } catch (Exception e) {
            // 💡 [핵심 해결] 백엔드에서 에러가 터지면, 연결을 끊는 대신 프론트엔드로 에러 메시지를 발송합니다!
            log.error("❌ 디버깅 소켓 처리 중 에러 발생: ", e);
            Map<String, String> errorMsg = new HashMap<>();
            errorMsg.put("type", "ERROR");
            errorMsg.put("data", "서버 디버깅 오류: " + e.getMessage());

            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorMsg)));
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        debugStrategies.forEach(s -> s.stopDebug(session.getId()));
    }
}