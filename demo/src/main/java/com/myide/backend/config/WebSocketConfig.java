// 경로: src/main/java/com/myide/backend/config/WebSocketConfig.java
package com.myide.backend.config;

import com.myide.backend.handler.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final RunWebSocketHandler runWebSocketHandler;
    private final DebugWebSocketHandler debugWebSocketHandler;
    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final CollaborationWebSocketHandler collaborationWebSocketHandler;
    private final WorkspaceEventWebSocketHandler workspaceEventWebSocketHandler;

    private static final String[] ALLOWED_ORIGIN_PATTERNS = {
            "http://localhost:*",
            "http://127.0.0.1:*",

            // 같은 와이파이/핫스팟에서 접속할 때 사용하는 사설 IP 대역
            "http://192.168.*.*:*",
            "http://10.*.*.*:*",
            "http://172.16.*.*:*",
            "http://172.17.*.*:*",
            "http://172.18.*.*:*",
            "http://172.19.*.*:*",
            "http://172.20.*.*:*",
            "http://172.21.*.*:*",
            "http://172.22.*.*:*",
            "http://172.23.*.*:*",
            "http://172.24.*.*:*",
            "http://172.25.*.*:*",
            "http://172.26.*.*:*",
            "http://172.27.*.*:*",
            "http://172.28.*.*:*",
            "http://172.29.*.*:*",
            "http://172.30.*.*:*",
            "http://172.31.*.*:*"
    };

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(runWebSocketHandler, "/ws/run")
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);

        registry.addHandler(debugWebSocketHandler, "/ws/debug")
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);

        registry.addHandler(terminalWebSocketHandler, "/ws/terminal")
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);

        registry.addHandler(collaborationWebSocketHandler, "/ws/collab")
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);

        // 파일 트리 변경 이벤트용
        registry.addHandler(workspaceEventWebSocketHandler, "/ws/workspace-events")
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);
    }
}