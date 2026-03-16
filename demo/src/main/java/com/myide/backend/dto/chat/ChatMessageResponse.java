// 경로: src/main/java/com/myide/backend/dto/chat/ChatMessageResponse.java
package com.myide.backend.dto.chat;

import com.myide.backend.domain.chat.ChatMessage;
import com.myide.backend.domain.chat.MessageType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatMessageResponse {
    private Long id;
    private String workspaceId;
    private Long senderId;
    private Long receiverId;
    private String senderName;
    private String content;
    private MessageType type;
    private String createdAt;

    public static ChatMessageResponse from(ChatMessage message) {
        return ChatMessageResponse.builder()
                .id(message.getId())
                .workspaceId(message.getWorkspaceId())
                .senderId(message.getSenderId())
                .receiverId(message.getReceiverId())
                .senderName(message.getSenderName())
                .content(message.getContent())
                .type(message.getType())
                // 시간을 예쁘게 포맷팅 (예: "10:05") 하거나 ISO로 보내서 프론트가 처리하도록 함
                .createdAt(message.getCreatedAt().toString())
                .build();
    }
}