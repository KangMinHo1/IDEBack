package com.myide.backend.controller;

import com.myide.backend.dto.chat.ChatMessageRequest;
import com.myide.backend.dto.chat.ChatMessageResponse;
import com.myide.backend.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(ChatMessageRequest request) {
        // 1. DB에 메시지 저장
        ChatMessageResponse savedMessage = chatService.saveMessage(request);

        // 2. 수신자에 따라 다른 토픽(방)으로 쏩니다!
        if (savedMessage.getReceiverId() == null) {
            // 📢 모두에게 (Public 단톡방)
            messagingTemplate.convertAndSend("/topic/workspace/" + request.getWorkspaceId(), savedMessage);
        } else {
            // 👤 특정 팀원에게 (1:1 귓속말) -> 나와 상대방의 개인 구독 채널로 각각 발송!
            messagingTemplate.convertAndSend("/topic/workspace/" + request.getWorkspaceId() + "/user/" + savedMessage.getReceiverId(), savedMessage);
            messagingTemplate.convertAndSend("/topic/workspace/" + request.getWorkspaceId() + "/user/" + savedMessage.getSenderId(), savedMessage);
        }
    }

    @GetMapping("/api/workspaces/{workspaceId}/chat")
    @CrossOrigin(origins = "*")
    public ResponseEntity<List<ChatMessageResponse>> getChatHistory(
            @PathVariable String workspaceId,
            @RequestParam Long userId) { // 💡 내 메시지만 필터링하기 위해 프론트에서 보내주는 userId를 받습니다.
        return ResponseEntity.ok(chatService.getChatHistory(workspaceId, userId));
    }
}