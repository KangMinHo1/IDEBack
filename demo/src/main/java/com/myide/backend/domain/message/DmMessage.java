package com.myide.backend.domain.message;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "dm_messages",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_dm_client",
                        columnNames = {
                                "sender_id",
                                "client_id"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_dm_history",
                        columnList = "conversation_id, id"
                ),
                @Index(
                        name = "idx_dm_unread",
                        columnList = "receiver_id, read_at, conversation_id"
                )
        }
)
public class DmMessage {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY
    )
    private Long id;


    /*
     * ==========================================
     * 대화방
     * ==========================================
     */

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "conversation_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_dm_message_conversation"
            )
    )
    private DmConversation conversation;


    /*
     * ==========================================
     * 보내는 사용자
     * ==========================================
     */

    @Column(
            name = "sender_id",
            nullable = false
    )
    private Long senderId;


    /*
     * ==========================================
     * 받는 사용자
     * ==========================================
     */

    @Column(
            name = "receiver_id",
            nullable = false
    )
    private Long receiverId;


    /*
     * ==========================================
     * 프론트 전송 식별자
     *
     * 같은 요청 재전송 시
     * 메시지 중복 저장 방지
     * ==========================================
     */

    @Column(
            name = "client_id",
            nullable = false,
            length = 36
    )
    private String clientId;


    /*
     * ==========================================
     * 메시지 내용
     * ==========================================
     */

    @Column(
            name = "content",
            nullable = false,
            length = 2000
    )
    private String content;


    /*
     * ==========================================
     * 시간
     * ==========================================
     */

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;


    @Column(
            name = "read_at"
    )
    private LocalDateTime readAt;


    /*
     * ==========================================
     * 생성
     * ==========================================
     */

    public static DmMessage create(
            DmConversation conversation,
            Long senderId,
            Long receiverId,
            String clientId,
            String content
    ) {

        DmMessage message =
                new DmMessage();


        message.conversation =
                conversation;


        message.senderId =
                senderId;


        message.receiverId =
                receiverId;


        message.clientId =
                clientId;


        message.content =
                content;


        message.createdAt =
                LocalDateTime.now();


        return message;
    }


    /*
     * ==========================================
     * 읽음 처리
     * ==========================================
     */

    public void markRead() {

        if (readAt == null) {

            readAt =
                    LocalDateTime.now();
        }
    }


    @PrePersist
    private void prePersist() {

        if (createdAt == null) {

            createdAt =
                    LocalDateTime.now();
        }
    }
}