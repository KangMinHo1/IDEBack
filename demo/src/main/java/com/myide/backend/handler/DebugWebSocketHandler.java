package com.myide.backend.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import com.myide.backend.service.WorkspaceService;
import com.myide.backend.service.debug.DebugStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
// 💡 [추가됨] 트랜잭션 처리를 위한 임포트
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DebugWebSocketHandler extends TextWebSocketHandler {

    private final List<DebugStrategy> debugStrategies;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;

    // 💡 [핵심 해결] 이 어노테이션이 DB 세션을 꽉 붙잡아줘서 Lazy 에러가 발생하지 않게 만듭니다!
    @Transactional(readOnly = true)
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            if (!json.has("type")) return;
            String type = json.get("type").asText();

            if ("START".equals(type)) {
                String workspaceId = json.get("workspaceId").asText();
                String projectName = json.get("projectName").asText();
                String branchName = json.has("branchName") ? json.get("branchName").asText() : "master";
                String filePath = json.has("filePath") ? json.get("filePath").asText() : "";

                // 1. 실행 전 폴더 검증!
                Path checkPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
                if (!checkPath.toFile().exists()) {
                    throw new RuntimeException("디버깅할 프로젝트 경로를 찾을 수 없습니다: " + checkPath.toString());
                }

                // 2. 성능 최적화: findAll() 대신 Workspace를 통해 해당 프로젝트 탐색
                Workspace workspace = workspaceRepository.findById(workspaceId)
                        .orElseThrow(() -> new RuntimeException("워크스페이스를 찾을 수 없습니다."));

                // ✨ @Transactional 덕분에 여기서 getProjects()를 호출해도 에러가 나지 않습니다!
                Project project = workspace.getProjects().stream()
                        .filter(p -> p.getName().equals(projectName))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("해당 프로젝트를 찾을 수 없습니다."));

                LanguageType language = project.getLanguage();

                JsonNode breakpointsNode = json.get("breakpoints");
                List<Map<String, Object>> breakpoints = objectMapper.convertValue(
                        breakpointsNode,
                        new TypeReference<List<Map<String, Object>>>() {}
                );

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