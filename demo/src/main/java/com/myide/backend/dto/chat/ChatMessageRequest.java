// 경로: src/main/java/com/myide/backend/dto/chat/ChatMessageRequest.java
package com.myide.backend.dto.chat;

import com.myide.backend.domain.chat.MessageType;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChatMessageRequest {
    private String workspaceId;
    private Long senderId;
    private Long receiverId;
    private String senderName;
    private String content;
    private MessageType type;
}