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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(runWebSocketHandler, "/ws/run").setAllowedOrigins("*");
        registry.addHandler(debugWebSocketHandler, "/ws/debug").setAllowedOrigins("*");
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal").setAllowedOrigins("*");


        registry.addHandler(collaborationWebSocketHandler, "/ws/collab").setAllowedOrigins("*");

        // 파일 트리 변경 이벤트용
        registry.addHandler(workspaceEventWebSocketHandler, "/ws/workspace-events").setAllowedOrigins("*");
    }
}