package com.myide.backend.repository.message;

import com.myide.backend.domain.message.DmMessage;

import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DmMessageRepository
        extends JpaRepository<DmMessage, Long> {


    /*
     * ==========================================
     * clientId 중복 확인
     * ==========================================
     */

    Optional<DmMessage>
    findBySenderIdAndClientId(
            Long senderId,
            String clientId
    );


    /*
     * ==========================================
     * 대화방 최신 메시지
     * ==========================================
     */

    Optional<DmMessage>
    findFirstByConversation_IdOrderByIdDesc(
            Long conversationId
    );


    /*
     * ==========================================
     * 특정 대화방 안 읽은 개수
     * ==========================================
     */

    long
    countByConversation_IdAndReceiverIdAndReadAtIsNull(
            Long conversationId,
            Long receiverId
    );


    /*
     * ==========================================
     * 전체 안 읽은 메시지 개수
     * ==========================================
     */

    long
    countByReceiverIdAndReadAtIsNull(
            Long receiverId
    );


    /*
     * ==========================================
     * 이전 메시지 조회
     *
     * id DESC
     * ==========================================
     */

    List<DmMessage>
    findByConversation_IdAndIdLessThanOrderByIdDesc(
            Long conversationId,
            Long beforeId,
            Pageable pageable
    );


    /*
     * ==========================================
     * throughId가 해당 대화의 메시지인지 확인
     * ==========================================
     */

    boolean
    existsByIdAndConversation_Id(
            Long id,
            Long conversationId
    );


    /*
     * ==========================================
     * 읽음 처리
     * ==========================================
     */

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update DmMessage m
               set m.readAt = :readAt
             where m.conversation.id = :conversationId
               and m.receiverId = :receiverId
               and m.id <= :throughId
               and m.readAt is null
            """)
    int markReadThrough(
            @Param("conversationId")
            Long conversationId,

            @Param("receiverId")
            Long receiverId,

            @Param("throughId")
            Long throughId,

            @Param("readAt")
            LocalDateTime readAt
    );
}