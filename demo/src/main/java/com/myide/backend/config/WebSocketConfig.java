package com.myide.backend.config;

import com.myide.backend.handler.DebugWebSocketHandler;
import com.myide.backend.handler.RunWebSocketHandler;
import com.myide.backend.handler.TerminalWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final RunWebSocketHandler runWebSocketHandler;
    private final DebugWebSocketHandler debugWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 1. 터미널 (Xterm.js)
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal")
                .setAllowedOrigins("*");

        // 2. 코드 실행 (Run)
        registry.addHandler(runWebSocketHandler, "/ws/run")
                .setAllowedOrigins("*");

        registry.addHandler(debugWebSocketHandler, "/ws/debug")
                .setAllowedOrigins("*"); // 모든 도메인에서 접속 허용 (CORS 해결)
    }
}