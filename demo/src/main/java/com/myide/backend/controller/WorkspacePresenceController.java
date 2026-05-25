package com.myide.backend.controller;

import com.myide.backend.domain.User;
import com.myide.backend.dto.presence.PresenceMessage;
import com.myide.backend.dto.presence.PresenceType;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.security.WebSocketAuthChannelInterceptor;
import com.myide.backend.security.WebSocketSessionAuthRegistry;
import com.myide.backend.service.presence.WorkspacePresenceRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class WorkspacePresenceController {

    private final SimpMessagingTemplate messagingTemplate;
    private final WorkspacePresenceRegistry presenceRegistry;
    private final WebSocketSessionAuthRegistry sessionAuthRegistry;
    private final UserRepository userRepository;

    @MessageMapping("/presence/{workspaceId}")
    public void handlePresenceMessage(
            @DestinationVariable String workspaceId,
            @Payload PresenceMessage message,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        if (message == null || message.getType() == null) {
            return;
        }

        String sessionId = headerAccessor.getSessionId();
        Long authenticatedUserId = resolveAuthenticatedUserId(headerAccessor);

        if (authenticatedUserId == null) {
            log.warn(
                    "[Presence] Unauthorized message blocked. workspaceId={}, type={}, sessionId={}",
                    workspaceId,
                    message.getType(),
                    sessionId
            );

            sendError(workspaceId, "Unauthorized presence request.");

            return;
        }

        switch (message.getType()) {
            case JOIN -> handleJoin(workspaceId, sessionId, authenticatedUserId);

            case HEARTBEAT -> {
                presenceRegistry.heartbeat(workspaceId, sessionId, authenticatedUserId);
                broadcastState(workspaceId);
            }

            case LEAVE -> {
                presenceRegistry.leave(sessionId);
                broadcastState(workspaceId);
            }

            case STATE -> broadcastState(workspaceId);

            default -> sendError(workspaceId, "Unsupported presence type.");
        }
    }

    private void handleJoin(
            String workspaceId,
            String sessionId,
            Long authenticatedUserId
    ) {
        User user = userRepository.findById(authenticatedUserId)
                .orElse(null);

        String nickname = user != null ? user.getNickname() : "User";
        String email = user != null ? user.getEmail() : "";
        String profileImageUrl = user != null ? user.getProfileImageUrl() : null;

        presenceRegistry.join(
                workspaceId,
                sessionId,
                authenticatedUserId,
                nickname,
                email,
                profileImageUrl
        );

        log.info(
                "[Presence] JOIN workspaceId={}, userId={}, sessionId={}",
                workspaceId,
                authenticatedUserId,
                sessionId
        );

        broadcastState(workspaceId);
    }

    public void broadcastState(String workspaceId) {
        PresenceMessage stateMessage = PresenceMessage.builder()
                .type(PresenceType.STATE)
                .workspaceId(workspaceId)
                .members(presenceRegistry.getOnlineMembers(workspaceId))
                .build();

        messagingTemplate.convertAndSend(
                "/topic/workspace/" + workspaceId + "/presence",
                stateMessage
        );
    }

    private void sendError(String workspaceId, String errorMessage) {
        PresenceMessage error = PresenceMessage.builder()
                .type(PresenceType.ERROR)
                .workspaceId(workspaceId)
                .errorMessage(errorMessage)
                .build();

        messagingTemplate.convertAndSend(
                "/topic/workspace/" + workspaceId + "/presence",
                error
        );
    }

    private Long resolveAuthenticatedUserId(SimpMessageHeaderAccessor headerAccessor) {
        if (headerAccessor == null) {
            return null;
        }

        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();

        if (sessionAttributes != null) {
            Object userId = sessionAttributes.get(WebSocketAuthChannelInterceptor.AUTH_USER_ID);
            Long parsedUserId = toLongOrNull(userId);

            if (parsedUserId != null) {
                return parsedUserId;
            }
        }

        Principal principal = headerAccessor.getUser();

        if (principal != null) {
            Long parsedUserId = toLongOrNull(principal.getName());

            if (parsedUserId != null) {
                return parsedUserId;
            }
        }

        return sessionAuthRegistry
                .getUserId(headerAccessor.getSessionId())
                .orElse(null);
    }

    private Long toLongOrNull(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Long longValue) {
            return longValue;
        }

        if (value instanceof Integer integerValue) {
            return integerValue.longValue();
        }

        if (value instanceof Number numberValue) {
            return numberValue.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}