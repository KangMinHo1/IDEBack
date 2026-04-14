package com.myide.backend.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType; // 💡 새롭게 만든 Enum 임포트!
import com.myide.backend.service.DockerService;
import com.myide.backend.service.WorkspaceService;
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
    private final WorkspaceService workspaceService;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode json = objectMapper.readTree(message.getPayload());
        String type = json.has("type") ? json.get("type").asText() : "RUN";

        // 컨테이너 종료 요청
        if ("STOP".equals(type)) {
            dockerService.stopContainer(session.getId());
            return;
        }

        // 프로세스 입력 (System.in 등) 요청
        if ("INPUT".equals(type)) {
            String input = json.get("input").asText();
            dockerService.writeToProcess(session.getId(), input);
            return;
        }

        // 실행(RUN) 기본 파라미터 파싱
        String workspaceId = json.get("workspaceId").asText();
        String projectName = json.get("projectName").asText();
        String branchName = json.has("branchName") ? json.get("branchName").asText() : "master";
        String filePath = json.has("filePath") ? json.get("filePath").asText() : "";
        LanguageType language = LanguageType.valueOf(json.get("language").asText().toUpperCase());

        // 💡 [버그 해결 포인트 1] 프론트엔드에서 보낸 templateType을 문자열로 꺼냅니다. (없으면 기본값 CONSOLE)
        String templateStr = json.has("templateType") ? json.get("templateType").asText().toUpperCase() : "CONSOLE";
        // 문자열을 우리가 만든 자바 Enum 객체로 변환합니다.
        TemplateType templateType = TemplateType.valueOf(templateStr);

        // 실행 전 실제 폴더가 존재하는지 검증
        Path checkPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
        if (!checkPath.toFile().exists()) {
            throw new RuntimeException("실행할 프로젝트 경로를 찾을 수 없습니다: " + checkPath.toString());
        }

        log.info("🚀 Run Request: Proj={}, Branch={}, Template={}", projectName, branchName, templateType);

        // 💡 [버그 해결 포인트 2] 7개의 파라미터를 정확하게 다 채워서 DockerService로 넘깁니다!
        int mappedPort = dockerService.runProject(
                session, workspaceId, projectName, branchName, filePath, language, templateType
        );

        // 💡 만약 실행된 게 서버(리액트/스프링)라서 0보다 큰 포트 번호를 받아왔다면,
        // 프론트엔드에게 "서버 켜졌으니 웹 화면 띄워!" 라고 신호를 보냅니다.
        if (mappedPort > 0) {
            session.sendMessage(new TextMessage("\n[SERVER_STARTED_PORT:" + mappedPort + "]\n"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        dockerService.stopContainer(session.getId());
    }
}