// 경로: src/main/java/com/myide/backend/config/WebSocketConfig.java
package com.myide.backend.config;

import com.myide.backend.handler.DebugWebSocketHandler;
import com.myide.backend.handler.RunWebSocketHandler;
import com.myide.backend.handler.TerminalWebSocketHandler;
import com.myide.backend.handler.CollaborationWebSocketHandler;
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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(runWebSocketHandler, "/ws/run").setAllowedOrigins("*");
        registry.addHandler(debugWebSocketHandler, "/ws/debug").setAllowedOrigins("*");
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal").setAllowedOrigins("*");

        // 💡 [수정] 이제 쿼리 파라미터로 받기 때문에 군더더기 없이 딱 하나만 열면 됩니다!
        registry.addHandler(collaborationWebSocketHandler, "/ws/collab").setAllowedOrigins("*");
    }
}