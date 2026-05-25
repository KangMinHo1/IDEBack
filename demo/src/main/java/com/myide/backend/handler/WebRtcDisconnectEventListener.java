package com.myide.backend.handler;

import com.myide.backend.dto.webrtc.SignalingType;
import com.myide.backend.dto.webrtc.WebRtcMessage;
import com.myide.backend.service.webrtc.VoiceRoomRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebRtcDisconnectEventListener {

    private final VoiceRoomRegistry voiceRoomRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();

        voiceRoomRegistry.leaveBySession(sessionId)
                .ifPresent(leaveEvent -> {
                    String workspaceId = leaveEvent.getWorkspaceId();

                    WebRtcMessage userLeftMessage = WebRtcMessage.builder()
                            .type(SignalingType.USER_LEFT)
                            .workspaceId(workspaceId)
                            .senderId(leaveEvent.getParticipant().getUserId())
                            .senderName(leaveEvent.getParticipant().getNickname())
                            .build();

                    messagingTemplate.convertAndSend(
                            "/topic/workspace/" + workspaceId + "/webrtc",
                            userLeftMessage
                    );

                    log.info(
                            "[WebRTC] disconnected. workspaceId={}, userId={}, sessionId={}",
                            workspaceId,
                            leaveEvent.getParticipant().getUserId(),
                            sessionId
                    );
                });
    }
}