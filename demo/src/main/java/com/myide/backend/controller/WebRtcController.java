package com.myide.backend.controller;

import com.myide.backend.dto.webrtc.SignalingType;
import com.myide.backend.dto.webrtc.VoiceChannel;
import com.myide.backend.dto.webrtc.VoiceParticipant;
import com.myide.backend.dto.webrtc.WebRtcMessage;
import com.myide.backend.security.WebSocketAuthChannelInterceptor;
import com.myide.backend.service.webrtc.VoiceChannelService;
import com.myide.backend.service.webrtc.VoiceRoomRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class WebRtcController {

    private final SimpMessagingTemplate messagingTemplate;
    private final VoiceRoomRegistry voiceRoomRegistry;
    private final VoiceChannelService voiceChannelService;

    @MessageMapping("/webrtc/{workspaceId}")
    public void handleWebRtcMessage(
            @DestinationVariable String workspaceId,
            @Payload WebRtcMessage message,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        if (message == null || message.getType() == null) {
            return;
        }

        String channelId = normalizeChannelId(message.getChannelId());

        Long authenticatedUserId = resolveAuthenticatedUserId(headerAccessor);
        Long claimedSenderId = message.getSenderId();

        if (authenticatedUserId == null) {
            log.warn(
                    "[WebRTC] Unauthorized request blocked. workspaceId={}, channelId={}, type={}, claimedSenderId={}",
                    workspaceId,
                    channelId,
                    message.getType(),
                    claimedSenderId
            );
            return;
        }

        /*
         * 중요:
         * 프론트에서 보낸 senderId는 신뢰하지 않습니다.
         * JWT에서 검증된 userId로 강제 덮어씁니다.
         */
        message.setWorkspaceId(workspaceId);
        message.setChannelId(channelId);
        message.setSenderId(authenticatedUserId);

        log.info(
                "[WebRTC] workspaceId={}, channelId={}, type={}, authenticatedSenderId={}, claimedSenderId={}, receiverId={}",
                workspaceId,
                channelId,
                message.getType(),
                authenticatedUserId,
                claimedSenderId,
                message.getReceiverId()
        );

        switch (message.getType()) {
            case CHANNELS -> handleChannels(workspaceId, message);

            case CREATE_CHANNEL -> handleCreateChannel(workspaceId, message);

            case UPDATE_CHANNEL -> handleUpdateChannel(workspaceId, channelId, message);

            case DELETE_CHANNEL -> handleDeleteChannel(workspaceId, message);

            case JOIN -> handleJoin(workspaceId, channelId, message, headerAccessor.getSessionId());

            case LEAVE -> handleLeave(workspaceId, channelId, message, headerAccessor.getSessionId());

            case OFFER, ANSWER, ICE -> handlePeerSignaling(workspaceId, channelId, message);

            case MUTE -> handleMuteChanged(workspaceId, channelId, message, true);

            case UNMUTE -> handleMuteChanged(workspaceId, channelId, message, false);

            default -> sendError(
                    workspaceId,
                    channelId,
                    message.getSenderId(),
                    "Unsupported signaling type."
            );
        }
    }

    private void handleChannels(String workspaceId, WebRtcMessage message) {
        List<VoiceChannel> channels = voiceChannelService.getChannels(workspaceId);

        WebRtcMessage response = WebRtcMessage.builder()
                .type(SignalingType.CHANNELS)
                .workspaceId(workspaceId)
                .channelId(normalizeChannelId(message.getChannelId()))
                .receiverId(message.getSenderId())
                .channels(channels)
                .build();

        broadcast(workspaceId, response);
    }

    private void handleCreateChannel(String workspaceId, WebRtcMessage message) {
        if (message.getSenderId() == null) {
            sendError(
                    workspaceId,
                    VoiceChannelService.DEFAULT_CHANNEL_ID,
                    null,
                    "senderId is required for CREATE_CHANNEL."
            );
            return;
        }

        String channelName = resolveRequiredChannelName(message, "새 음성 채널");
        String channelIcon = resolveRequiredChannelIcon(
                message,
                VoiceChannelService.DEFAULT_CHANNEL_ICON
        );

        VoiceChannel channel = voiceChannelService.createChannel(
                workspaceId,
                channelName,
                channelIcon,
                message.getSenderId()
        );

        List<VoiceChannel> channels = voiceChannelService.getChannels(workspaceId);

        WebRtcMessage response = WebRtcMessage.builder()
                .type(SignalingType.CHANNEL_CREATED)
                .workspaceId(workspaceId)
                .channelId(channel.getChannelId())
                .senderId(message.getSenderId())
                .senderName(message.getSenderName())
                .channel(channel)
                .channels(channels)
                .build();

        broadcast(workspaceId, response);
    }

    private void handleUpdateChannel(
            String workspaceId,
            String channelId,
            WebRtcMessage message
    ) {
        if (message.getSenderId() == null) {
            sendError(
                    workspaceId,
                    channelId,
                    null,
                    "senderId is required for UPDATE_CHANNEL."
            );
            return;
        }

        String nextChannelName = resolveOptionalChannelName(message);
        String nextChannelIcon = resolveOptionalChannelIcon(message);

        if (
                (nextChannelName == null || nextChannelName.isBlank())
                        && (nextChannelIcon == null || nextChannelIcon.isBlank())
        ) {
            sendError(
                    workspaceId,
                    channelId,
                    message.getSenderId(),
                    "channelName or channelIcon is required for UPDATE_CHANNEL."
            );
            return;
        }

        voiceChannelService.updateChannel(
                        workspaceId,
                        channelId,
                        nextChannelName,
                        nextChannelIcon
                )
                .ifPresentOrElse(
                        result -> {
                            WebRtcMessage response = WebRtcMessage.builder()
                                    .type(SignalingType.CHANNEL_UPDATED)
                                    .workspaceId(workspaceId)
                                    .channelId(result.getChannel().getChannelId())
                                    .senderId(message.getSenderId())
                                    .senderName(message.getSenderName())
                                    .channel(result.getChannel())
                                    .channels(result.getChannels())
                                    .build();

                            broadcast(workspaceId, response);
                        },
                        () -> sendError(
                                workspaceId,
                                channelId,
                                message.getSenderId(),
                                "Voice channel does not exist."
                        )
                );
    }

    private void handleDeleteChannel(String workspaceId, WebRtcMessage message) {
        if (message.getSenderId() == null) {
            sendError(
                    workspaceId,
                    normalizeChannelId(message.getChannelId()),
                    null,
                    "senderId is required for DELETE_CHANNEL."
            );
            return;
        }

        String channelId = normalizeChannelId(message.getChannelId());

        voiceChannelService.deleteChannel(workspaceId, channelId)
                .ifPresentOrElse(
                        result -> {
                            List<VoiceParticipant> removedParticipants =
                                    voiceRoomRegistry.removeChannelParticipants(
                                            workspaceId,
                                            result.getDeletedChannel().getChannelId()
                                    );

                            WebRtcMessage response = WebRtcMessage.builder()
                                    .type(SignalingType.CHANNEL_DELETED)
                                    .workspaceId(workspaceId)
                                    .channelId(result.getDeletedChannel().getChannelId())
                                    .senderId(message.getSenderId())
                                    .senderName(message.getSenderName())
                                    .channel(result.getDeletedChannel())
                                    .channels(result.getChannels())
                                    .participants(removedParticipants)
                                    .build();

                            broadcast(workspaceId, response);
                        },
                        () -> sendError(
                                workspaceId,
                                channelId,
                                message.getSenderId(),
                                "Cannot delete this voice channel."
                        )
                );
    }

    private void handleJoin(
            String workspaceId,
            String channelId,
            WebRtcMessage message,
            String sessionId
    ) {
        if (message.getSenderId() == null) {
            sendError(workspaceId, channelId, null, "senderId is required for JOIN.");
            return;
        }

        if (!voiceChannelService.existsChannel(workspaceId, channelId)) {
            sendError(
                    workspaceId,
                    channelId,
                    message.getSenderId(),
                    "Voice channel does not exist."
            );
            return;
        }

        VoiceRoomRegistry.JoinResult joinResult = voiceRoomRegistry.join(
                workspaceId,
                channelId,
                message.getSenderId(),
                message.getSenderName(),
                sessionId
        );

        for (VoiceRoomRegistry.LeaveEvent previousLeaveEvent : joinResult.getPreviousLeaveEvents()) {
            WebRtcMessage userLeftMessage = WebRtcMessage.builder()
                    .type(SignalingType.USER_LEFT)
                    .workspaceId(previousLeaveEvent.getWorkspaceId())
                    .channelId(previousLeaveEvent.getChannelId())
                    .senderId(previousLeaveEvent.getParticipant().getUserId())
                    .senderName(previousLeaveEvent.getParticipant().getNickname())
                    .build();

            broadcast(previousLeaveEvent.getWorkspaceId(), userLeftMessage);
        }

        WebRtcMessage roomUsersMessage = WebRtcMessage.builder()
                .type(SignalingType.ROOM_USERS)
                .workspaceId(workspaceId)
                .channelId(channelId)
                .senderId(message.getSenderId())
                .receiverId(message.getSenderId())
                .participants(joinResult.getParticipants())
                .channels(voiceChannelService.getChannels(workspaceId))
                .build();

        broadcast(workspaceId, roomUsersMessage);

        WebRtcMessage userJoinedMessage = WebRtcMessage.builder()
                .type(SignalingType.USER_JOINED)
                .workspaceId(workspaceId)
                .channelId(channelId)
                .senderId(message.getSenderId())
                .senderName(message.getSenderName())
                .muted(false)
                .build();

        broadcast(workspaceId, userJoinedMessage);
    }

    private void handleLeave(
            String workspaceId,
            String channelId,
            WebRtcMessage message,
            String sessionId
    ) {
        if (message.getSenderId() == null) {
            sendError(workspaceId, channelId, null, "senderId is required for LEAVE.");
            return;
        }

        voiceRoomRegistry.leave(workspaceId, channelId, message.getSenderId(), sessionId)
                .ifPresent(leaveEvent -> {
                    WebRtcMessage userLeftMessage = WebRtcMessage.builder()
                            .type(SignalingType.USER_LEFT)
                            .workspaceId(leaveEvent.getWorkspaceId())
                            .channelId(leaveEvent.getChannelId())
                            .senderId(leaveEvent.getParticipant().getUserId())
                            .senderName(leaveEvent.getParticipant().getNickname())
                            .build();

                    broadcast(leaveEvent.getWorkspaceId(), userLeftMessage);
                });
    }

    private void handlePeerSignaling(
            String workspaceId,
            String channelId,
            WebRtcMessage message
    ) {
        if (message.getSenderId() == null) {
            sendError(workspaceId, channelId, null, "senderId is required.");
            return;
        }

        if (message.getReceiverId() == null) {
            sendError(workspaceId, channelId, message.getSenderId(), "receiverId is required.");
            return;
        }

        if (!voiceChannelService.existsChannel(workspaceId, channelId)) {
            sendError(
                    workspaceId,
                    channelId,
                    message.getSenderId(),
                    "Voice channel does not exist."
            );
            return;
        }

        if (!voiceRoomRegistry.isParticipant(workspaceId, channelId, message.getSenderId())) {
            sendError(
                    workspaceId,
                    channelId,
                    message.getSenderId(),
                    "Sender is not in this voice channel."
            );
            return;
        }

        if (!voiceRoomRegistry.isParticipant(workspaceId, channelId, message.getReceiverId())) {
            sendError(
                    workspaceId,
                    channelId,
                    message.getSenderId(),
                    "Receiver is not in this voice channel."
            );
            return;
        }

        broadcast(workspaceId, message);
    }

    private void handleMuteChanged(
            String workspaceId,
            String channelId,
            WebRtcMessage message,
            boolean muted
    ) {
        if (message.getSenderId() == null) {
            sendError(workspaceId, channelId, null, "senderId is required for mute state.");
            return;
        }

        voiceRoomRegistry.updateMuted(workspaceId, channelId, message.getSenderId(), muted)
                .ifPresent(participant -> {
                    WebRtcMessage muteMessage = WebRtcMessage.builder()
                            .type(muted ? SignalingType.MUTE : SignalingType.UNMUTE)
                            .workspaceId(workspaceId)
                            .channelId(channelId)
                            .senderId(participant.getUserId())
                            .senderName(participant.getNickname())
                            .muted(muted)
                            .build();

                    broadcast(workspaceId, muteMessage);
                });
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
            return toLongOrNull(principal.getName());
        }

        return null;
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

    private void sendError(
            String workspaceId,
            String channelId,
            Long receiverId,
            String errorMessage
    ) {
        WebRtcMessage message = WebRtcMessage.builder()
                .type(SignalingType.ERROR)
                .workspaceId(workspaceId)
                .channelId(channelId)
                .receiverId(receiverId)
                .errorMessage(errorMessage)
                .build();

        broadcast(workspaceId, message);
    }

    private void broadcast(String workspaceId, WebRtcMessage message) {
        messagingTemplate.convertAndSend(
                "/topic/workspace/" + workspaceId + "/webrtc",
                message
        );
    }

    private String normalizeChannelId(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return VoiceChannelService.DEFAULT_CHANNEL_ID;
        }

        return channelId.trim();
    }

    private String resolveRequiredChannelName(
            WebRtcMessage message,
            String fallback
    ) {
        String directValue = message.getChannelName();

        if (directValue != null && !directValue.isBlank()) {
            return directValue;
        }

        Map<String, Object> payload = message.getPayload();

        if (payload == null) {
            return fallback;
        }

        Object value = payload.get("name");

        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }

        return String.valueOf(value);
    }

    private String resolveRequiredChannelIcon(
            WebRtcMessage message,
            String fallback
    ) {
        String directValue = message.getChannelIcon();

        if (directValue != null && !directValue.isBlank()) {
            return directValue;
        }

        Map<String, Object> payload = message.getPayload();

        if (payload == null) {
            return fallback;
        }

        Object value = payload.get("icon");

        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }

        return String.valueOf(value);
    }

    private String resolveOptionalChannelName(WebRtcMessage message) {
        String directValue = message.getChannelName();

        if (directValue != null && !directValue.isBlank()) {
            return directValue;
        }

        Map<String, Object> payload = message.getPayload();

        if (payload == null) {
            return null;
        }

        Object value = payload.get("name");

        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }

        return String.valueOf(value);
    }

    private String resolveOptionalChannelIcon(WebRtcMessage message) {
        String directValue = message.getChannelIcon();

        if (directValue != null && !directValue.isBlank()) {
            return directValue;
        }

        Map<String, Object> payload = message.getPayload();

        if (payload == null) {
            return null;
        }

        Object value = payload.get("icon");

        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }

        return String.valueOf(value);
    }
}