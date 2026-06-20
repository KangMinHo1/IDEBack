package com.myide.backend.controller;

import com.myide.backend.domain.notification.NotificationType;
import com.myide.backend.dto.chat.ChatMessageRequest;
import com.myide.backend.dto.chat.ChatMessageResponse;
import com.myide.backend.service.ChatService;
import com.myide.backend.service.NotificationService;
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
    private final NotificationService notificationService;

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(ChatMessageRequest request) {
        // 1. DB에 메시지 저장
        ChatMessageResponse savedMessage = chatService.saveMessage(request);

        // 2. 전체 팀 채팅인 경우
        if (savedMessage.getReceiverId() == null) {
            // 채팅방 구독자에게 실시간 메시지 전송
            messagingTemplate.convertAndSend(
                    "/topic/workspace/" + request.getWorkspaceId(),
                    savedMessage
            );

            // 메시지 보낸 사람을 제외한 팀원들에게 알림 생성
            notificationService.notifyWorkspaceMembersExcept(
                    request.getWorkspaceId(),
                    savedMessage.getSenderId(),
                    NotificationType.CHAT,
                    "팀 채팅",
                    savedMessage.getSenderName() + ": " + savedMessage.getContent(),
                    "/projects/" + request.getWorkspaceId() + "?mode=team"
            );

            return;
        }

        // 3. 1:1 채팅인 경우
        messagingTemplate.convertAndSend(
                "/topic/workspace/" + request.getWorkspaceId() + "/user/" + savedMessage.getReceiverId(),
                savedMessage
        );

        messagingTemplate.convertAndSend(
                "/topic/workspace/" + request.getWorkspaceId() + "/user/" + savedMessage.getSenderId(),
                savedMessage
        );

        // 1:1 채팅 수신자에게만 알림 생성
        notificationService.notifyUser(
                savedMessage.getReceiverId(),
                request.getWorkspaceId(),
                NotificationType.CHAT,
                "개인 채팅",
                savedMessage.getSenderName() + ": " + savedMessage.getContent(),
                "/projects/" + request.getWorkspaceId() + "?mode=team"
        );
    }

    @GetMapping("/api/workspaces/{workspaceId}/chat")
    public ResponseEntity<List<ChatMessageResponse>> getChatHistory(
            @PathVariable String workspaceId,
            @RequestParam Long userId
    ) {
        return ResponseEntity.ok(chatService.getChatHistory(workspaceId, userId));
    }
}