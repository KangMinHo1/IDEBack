package com.myide.backend.handler;

// ... (import 생략: 위와 동일한 구조) ...
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
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
public class RunWebSocketHandler extends TextWebSocketHandler {

    // 코드를 실행할 때는 도커(Docker)라는 가상 환경을 사용하므로 DockerService를 주입받습니다.
    private final DockerService dockerService;
    private final ObjectMapper objectMapper;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode json = objectMapper.readTree(message.getPayload());
        // 메시지 타입이 안 왔다면 기본값으로 "RUN"을 줍니다.
        String type = json.has("type") ? json.get("type").asText() : "RUN";

        // 실행 중지 요청 처리
        if ("STOP".equals(type)) {
            dockerService.stopContainer(session.getId()); // 해당 유저의 도커 컨테이너를 멈춥니다.
            return; // 멈췄으니 더 이상 아래 코드를 실행하지 않고 끝냅니다.
        }

        // 터미널 입력 요청 처리
        if ("INPUT".equals(type)) {
            String input = json.get("input").asText();
            dockerService.writeToProcess(session.getId(), input); // 도커 컨테이너 안으로 키보드 입력을 밀어넣습니다.
            return;
        }

        // 실행("RUN")을 위한 필수 데이터들을 JSON에서 꺼냅니다.
        String workspaceId = json.get("workspaceId").asText();
        String projectName = json.get("projectName").asText();
        String branchName = json.has("branchName") ? json.get("branchName").asText() : "main-repo";
        String filePath = json.has("filePath") ? json.get("filePath").asText() : "";

        // 어떤 언어(예: JAVA, PYTHON 등)인지 꺼내서 enum(열거형) 객체로 변환합니다. 대문자로 맞춰줍니다.
        LanguageType language = LanguageType.valueOf(json.get("language").asText().toUpperCase());

        // 콘솔에 멋지게 실행된다고 로그를 찍어봅니다!
        log.info("🚀 Run Request: Proj={}, Branch={}", projectName, branchName);

        // DockerService에게 "이 언어로 이 파일 좀 컨테이너 안에서 실행해줘!" 라고 부탁합니다.
        dockerService.runProject(session, workspaceId, projectName, branchName, filePath, language);
    }

    // 웹소켓 연결이 끊기면
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // 뒤에서 몰래 돌아가고 있을지 모르는 도커 컨테이너를 깔끔하게 종료해줍니다 (메모리 낭비 방지).
        dockerService.stopContainer(session.getId());
    }
}