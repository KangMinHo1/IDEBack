// 경로: src/main/java/com/myide/backend/config/WebSocketConfig.java
package com.myide.backend.config;

import com.myide.backend.handler.*;
import com.myide.backend.security.CollabHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /** 한 메시지의 최대 크기. 자세한 이유는 아래 createWebSocketContainer 주석 참고. */
    public static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    private final RunWebSocketHandler runWebSocketHandler;
    private final DebugWebSocketHandler debugWebSocketHandler;
    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final CollaborationWebSocketHandler collaborationWebSocketHandler;
    private final WorkspaceEventWebSocketHandler workspaceEventWebSocketHandler;
    private final CollabHandshakeInterceptor collabHandshakeInterceptor;

    /**
     * 접속을 받아 줄 출처 목록. 원본은 application.yml 의 app.cors.allowed-origins 하나뿐이고,
     * HTTP 쪽 CORS 설정(SecurityConfig)과 같은 값을 쓴다.
     *
     * 목록이 같아도 설정을 따로 두어야 하는 이유는, 웹소켓 핸드셰이크가 브라우저의
     * CORS 절차(사전 확인 요청)를 거치지 않기 때문이다. HTTP 쪽 필터는 여기에 관여하지
     * 못하므로 서버가 직접 Origin 헤더를 확인해야 한다.
     */
    @Value("${app.cors.allowed-origins}")
    private String[] allowedOriginPatterns;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(runWebSocketHandler, "/ws/run")
                .setAllowedOriginPatterns(allowedOriginPatterns);

        registry.addHandler(debugWebSocketHandler, "/ws/debug")
                .setAllowedOriginPatterns(allowedOriginPatterns);

        registry.addHandler(terminalWebSocketHandler, "/ws/terminal")
                .setAllowedOriginPatterns(allowedOriginPatterns);

        // 설계 문서와 코드 동시편집이 같은 엔드포인트를 쓴다.
        // 핸드셰이크 단계에서 JWT와 워크스페이스 멤버십을 확인한다.
        registry.addHandler(collaborationWebSocketHandler, "/ws/collab")
                .addInterceptors(collabHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOriginPatterns);

        // 파일 트리 변경 이벤트용
        registry.addHandler(workspaceEventWebSocketHandler, "/ws/workspace-events")
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }

    /**
     * 한 번에 주고받을 수 있는 메시지 크기.
     *
     * 이 설정이 없으면 톰캣 기본값인 8KB 가 적용된다. 그 한도로는 동시 편집이
     * 반쪽만 돈다 — 이름 한 줄을 고치는 작은 변경은 통과하지만, <b>AI 초안을
     * 적용하거나 되돌리기를 하면 문서 전체가 한 덩어리로 나가면서</b> 한도를
     * 넘고, 서버가 그 연결을 끊어 버린다(1009). 그래서 팀원 화면에는 아무것도
     * 안 나타나고 새로고침해야 보였다. 초안이 들어간 뒤에는 접속할 때 주고받는
     * 최초 동기화마저 8KB를 넘어 아예 못 붙는다.
     *
     * 코드 에디터도 같은 엔드포인트를 쓰므로 큰 파일에서 같은 문제를 겪는다.
     *
     * 1MB 로 잡은 이유는 지금 설계 문서 전체의 Yjs 바이너리가 수십 KB 수준이라
     * 여유가 충분하고, 버퍼는 연결마다 잡히므로 무한정 키울 것은 아니기
     * 때문이다. 이 한도마저 넘길 만큼 커지면 그때는 Node y-websocket 사이드카로
     * 옮긴다 — 방 이름 규약을 지켜 두었으므로 프론트는 그대로 두고 주소만
     * 바꾸면 된다.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();

        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);

        return container;
    }
}