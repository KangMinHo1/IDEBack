package com.myide.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.LanguageType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HtmlDebugStrategy implements DebugStrategy {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(LanguageType language) {
        // HTML과 C#(현재 버전에서는 C# 디버깅 스킵)을 이 클래스가 처리합니다.
        return language == LanguageType.HTML || language == LanguageType.CSHARP;
    }

    @Override
    public void startDebug(WebSocketSession session, String workspaceId, String projectName, String branchName, String filePath, List<Map<String, Object>> breakpoints) {
        try {
            Map<String, String> map = new HashMap<>();
            map.put("type", "OUTPUT");
            map.put("data", "[System] HTML/CSS 또는 C#은 현재 백엔드 디버깅을 지원하지 않습니다. HTML은 브라우저 개발자도구(F12)를 이용해주세요.\n");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(map)));

            // 바로 세션 닫기
            session.close();
        } catch (Exception e) {}
    }

    @Override
    public void handleCommand(String sessionId, String command) {}
    @Override
    public void input(String sessionId, String input) {}
    @Override
    public void stopDebug(String sessionId) {}
}