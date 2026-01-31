package com.myide.backend.config;

import com.myide.backend.handler.DebugWebSocketHandler;
import com.myide.backend.handler.RunWebSocketHandler;
import com.myide.backend.handler.TerminalWebSocketHandler; // [추가]
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
    private final TerminalWebSocketHandler terminalWebSocketHandler; // [추가]

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(runWebSocketHandler, "/ws/run").setAllowedOrigins("*");
        registry.addHandler(debugWebSocketHandler, "/ws/debug").setAllowedOrigins("*");
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal").setAllowedOrigins("*"); // [추가]
    }
}