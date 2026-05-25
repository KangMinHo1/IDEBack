package com.myide.backend.handler;

import com.myide.backend.controller.WorkspacePresenceController;
import com.myide.backend.service.presence.WorkspacePresenceRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@RequiredArgsConstructor
public class WorkspacePresenceDisconnectListener {

    private final WorkspacePresenceRegistry presenceRegistry;
    private final WorkspacePresenceController presenceController;

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();

        presenceRegistry.leave(sessionId)
                .ifPresent(presenceController::broadcastState);
    }
}