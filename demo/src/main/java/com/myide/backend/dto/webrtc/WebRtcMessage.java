package com.myide.backend.dto.webrtc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebRtcMessage {

    // 💡 String 대신 Enum 적용! (프론트가 대문자로 "OFFER"라고 보내면 자동 매핑됨)
    private SignalingType type;

    // 💡 우리 시스템의 User 엔티티 PK 타입에 맞춘 Long
    private Long senderId;
    private Long receiverId; // 특정 대상에게만 보내야 할 때 사용 (Offer, Answer 등)

    private String workspaceId; // 통신이 일어나는 방(Workspace UUID)

    // SDP 정보나 ICE Candidate 객체가 통째로 들어옵니다.
    private Object data;
}