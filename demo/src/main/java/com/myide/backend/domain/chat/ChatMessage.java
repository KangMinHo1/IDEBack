// 경로: src/main/java/com/myide/backend/domain/chat/ChatMessage.java
package com.myide.backend.domain.chat;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private Long senderId; // 보낸 사람의 회원 번호

    @Column(nullable = true) // 💡 모두에게 보내는 메시지는 null 허용
    private Long receiverId; //받는 사람의 회원 번호

    @Column(nullable = false)
    private String senderName; // 보낸 사람 이름 (닉네임)

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageType type;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}