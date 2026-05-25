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
         * 프론트에서 보낸 senderId는 신뢰하지 않습니다.
         * JWT에서 검증된 userId로 강제 덮어씁니다.
         */
        message.setWorkspaceId(workspaceId);
        message.setChannelId(channelId);
        message.setSenderId(authenticatedUserId);

        log.info(
                "[WebRTC] received type={}, workspaceId={}, channelId={}, authenticatedSenderId={}, claimedSenderId={}, receiverId={}",
                message.getType(),
                workspaceId,
                channelId,
                authenticatedUserId,
                claimedSenderId,
                message.getReceiverId()
        );

        switch (message.getType()) {
            case CHANNELS -> handleChannels(workspaceId, channelId, message);

            /*
             * 프론트가 음성 모달을 열거나 채널을 클릭할 때
             * 현재 채널 참여자 목록을 요청합니다.
             * 기존 코드에는 이 case가 없어서 Unsupported signaling type 에러가 발생했습니다.
             */
            case ROOM_USERS -> handleRoomUsers(workspaceId, channelId, message);

            case CREATE_CHANNEL -> handleCreateChannel(workspaceId, message);

            case UPDATE_CHANNEL -> handleUpdateChannel(workspaceId, channelId, message);

            case DELETE_CHANNEL -> handleDeleteChannel(workspaceId, message);

            case JOIN -> handleJoin(workspaceId, channelId, message, headerAccessor.getSessionId());

            case LEAVE -> handleLeave(workspaceId, channelId, message, headerAccessor.getSessionId());

            case OFFER, ANSWER, ICE -> handlePeerSignaling(workspaceId, channelId, message);

            case MUTE -> handleMuteChanged(workspaceId, channelId, message, true);

            case UNMUTE -> handleMuteChanged(workspaceId, channelId, message, false);

            /*
             * 아래 타입들은 서버가 클라이언트에게 내려주는 이벤트입니다.
             * 클라이언트가 실수로 보내도 에러 폭주를 만들지 않도록 무시합니다.
             */
            case USER_JOINED,
                 USER_LEFT,
                 CHANNEL_CREATED,
                 CHANNEL_UPDATED,
                 CHANNEL_DELETED,
                 ERROR -> log.debug(
                    "[WebRTC] Server-only event ignored. type={}, workspaceId={}, channelId={}, senderId={}",
                    message.getType(),
                    workspaceId,
                    channelId,
                    message.getSenderId()
            );

            default -> sendError(
                    workspaceId,
                    channelId,
                    message.getSenderId(),
                    "Unsupported signaling type: " + message.getType()
            );
        }
    }

    private void handleChannels(
            String workspaceId,
            String channelId,
            WebRtcMessage message
    ) {
        List<VoiceChannel> channels = voiceChannelService.getChannels(workspaceId);

        WebRtcMessage response = WebRtcMessage.builder()
                .type(SignalingType.CHANNELS)
                .workspaceId(workspaceId)
                .channelId(channelId)
                .receiverId(message.getSenderId())
                .channels(channels)
                .build();

        broadcast(workspaceId, response);
    }

    private void handleRoomUsers(
            String workspaceId,
            String channelId,
            WebRtcMessage message
    ) {
        if (!voiceChannelService.existsChannel(workspaceId, channelId)) {
            sendError(
                    workspaceId,
                    channelId,
                    message.getSenderId(),
                    "Voice channel does not exist."
            );
            return;
        }

        List<VoiceParticipant> participants =
                voiceRoomRegistry.getParticipants(workspaceId, channelId);

        WebRtcMessage response = WebRtcMessage.builder()
                .type(SignalingType.ROOM_USERS)
                .workspaceId(workspaceId)
                .channelId(channelId)
                .senderId(message.getSenderId())
                .receiverId(message.getSenderId())
                .participants(participants)
                .channels(voiceChannelService.getChannels(workspaceId))
                .build();

        broadcast(workspaceId, response);

        log.info(
                "[WebRTC] ROOM_USERS workspaceId={}, channelId={}, receiverId={}, participantCount={}",
                workspaceId,
                channelId,
                message.getSenderId(),
                participants.size()
        );
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

            broadcastRoomUsers(
                    previousLeaveEvent.getWorkspaceId(),
                    previousLeaveEvent.getChannelId()
            );
        }

        /*
         * JOIN한 본인에게 현재 방 사용자 목록을 내려줍니다.
         */
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

        /*
         * 기존 참여자들에게 새 사용자가 들어왔음을 알립니다.
         * 기존 참여자가 이 이벤트를 받고 OFFER를 생성합니다.
         */
        WebRtcMessage userJoinedMessage = WebRtcMessage.builder()
                .type(SignalingType.USER_JOINED)
                .workspaceId(workspaceId)
                .channelId(channelId)
                .senderId(message.getSenderId())
                .senderName(message.getSenderName())
                .muted(false)
                .build();

        broadcast(workspaceId, userJoinedMessage);

        /*
         * 채널 목록만 보고 있는 사용자도 인원 수와 참여자 목록이 갱신되도록
         * 현재 방 상태를 전체 broadcast합니다.
         */
        broadcastRoomUsers(workspaceId, channelId);

        log.info(
                "[WebRTC] JOIN workspaceId={}, channelId={}, userId={}, participantCount={}",
                workspaceId,
                channelId,
                message.getSenderId(),
                joinResult.getParticipants().size()
        );
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

                    broadcastRoomUsers(
                            leaveEvent.getWorkspaceId(),
                            leaveEvent.getChannelId()
                    );

                    log.info(
                            "[WebRTC] LEAVE workspaceId={}, channelId={}, userId={}",
                            leaveEvent.getWorkspaceId(),
                            leaveEvent.getChannelId(),
                            leaveEvent.getParticipant().getUserId()
                    );
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

        log.info(
                "[WebRTC] relay type={}, workspaceId={}, channelId={}, senderId={}, receiverId={}",
                message.getType(),
                workspaceId,
                channelId,
                message.getSenderId(),
                message.getReceiverId()
        );

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
                    broadcastRoomUsers(workspaceId, channelId);
                });
    }

    private void broadcastRoomUsers(String workspaceId, String channelId) {
        List<VoiceParticipant> participants =
                voiceRoomRegistry.getParticipants(workspaceId, channelId);

        WebRtcMessage stateMessage = WebRtcMessage.builder()
                .type(SignalingType.ROOM_USERS)
                .workspaceId(workspaceId)
                .channelId(channelId)
                .participants(participants)
                .channels(voiceChannelService.getChannels(workspaceId))
                .build();

        broadcast(workspaceId, stateMessage);
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