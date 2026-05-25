package com.myide.backend.dto.webrtc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebRtcMessage {

    private SignalingType type;

    private String workspaceId;

    private String channelId;

    private Long senderId;

    private String senderName;

    private Long receiverId;

    private Map<String, Object> payload;

    private Boolean muted;

    private String errorMessage;

    /*
     * 채널 생성/수정 요청에서 사용
     */
    private String channelName;

    private String channelIcon;

    /*
     * 채널 생성/수정/삭제 응답에서 사용
     */
    private VoiceChannel channel;

    /*
     * 채널 목록 응답에서 사용
     */
    private List<VoiceChannel> channels;

    /*
     * ROOM_USERS, CHANNEL_DELETED 등에서 사용
     */
    private List<VoiceParticipant> participants;
}