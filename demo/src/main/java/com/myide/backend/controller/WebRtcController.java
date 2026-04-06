package com.myide.backend.controller;

import com.myide.backend.dto.webrtc.WebRtcMessage;
import com.myide.backend.dto.webrtc.SignalingType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WebRtcController {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 🎧 WebRTC P2P 연결을 위한 시그널링(명함 교환) 라우터
     * 클라이언트 전송 경로: /app/webrtc/{workspaceId}
     */
    @MessageMapping("/webrtc/{workspaceId}")
    public void relaySignalingMessage(@DestinationVariable String workspaceId, @Payload WebRtcMessage message) {

        // 💡 현업 보안 포인트: 실제로는 여기서 JWT SecurityContext를 조회하여
        // message.getSenderId() 가 조작되지 않았는지 (현재 로그인한 본인이 맞는지) 검증해야 완벽합니다.

        log.info("📞 [WebRTC Signaling] Type: {}, Sender(User ID): {} -> Receiver(User ID): {}",
                message.getType(), message.getSenderId(), message.getReceiverId());

        // 특정인에게만 보내야 하는 메시지 (1:1 명함 교환)
        if (message.getType() == SignalingType.OFFER || message.getType() == SignalingType.ANSWER || message.getType() == SignalingType.ICE) {
            // 프론트엔드는 자기가 받을 메시지만 필터링할 수 있도록 설계합니다.
            messagingTemplate.convertAndSend("/topic/workspace/" + workspaceId + "/webrtc", message);
        }
        // 방 전체에 브로드캐스트 해야 하는 메시지 (나 들어왔어! / 나 나간다!)
        else if (message.getType() == SignalingType.JOIN || message.getType() == SignalingType.LEAVE) {
            messagingTemplate.convertAndSend("/topic/workspace/" + workspaceId + "/webrtc", message);
        }
    }
}