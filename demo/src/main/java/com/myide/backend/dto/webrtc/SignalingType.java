package com.myide.backend.dto.webrtc;

public enum SignalingType {
    JOIN,
    OFFER,
    ANSWER,
    ICE,
    LEAVE,
    SPEAKING   // 💡 [NEW] 누가 말하고 있는지 알려주는 상태 타입 추가!
}