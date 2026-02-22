package com.myide.backend.service;

import com.myide.backend.domain.LanguageType;
import org.springframework.web.socket.WebSocketSession;
import java.util.List;
import java.util.Map;

// 왕초보 주석: 모든 언어별 디버깅 서비스(Java, Python 등)는 무조건 이 인터페이스를 구현해야 합니다.
// 이렇게 하면 핸들러 입장에서는 이게 자바인지 파이썬인지 신경 안 쓰고 그냥 startDebug()만 호출하면 됩니다. (다형성)
public interface DebugStrategy {

    // 이 전략 클래스가 해당 언어(LanguageType)를 지원하는지 묻는 메서드입니다.
    boolean supports(LanguageType language);

    // 디버깅 시작
    void startDebug(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, List<Map<String, Object>> breakpoints);

    // 프론트에서 보낸 디버깅 명령(STEP_OVER, CONTINUE 등) 처리
    void handleCommand(String sessionId, String command);

    // 콘솔 입력 처리
    void input(String sessionId, String input);

    // 디버깅 강제 종료
    void stopDebug(String sessionId);
}