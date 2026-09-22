package com.myide.backend.repository.message;

import com.myide.backend.domain.message.DmConversation;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DmConversationRepository
        extends JpaRepository<DmConversation, Long> {


    /*
     * ==========================================
     * 사용자 두 명의 기존 대화 조회
     * ==========================================
     */

    Optional<DmConversation>
    findByUserLowAndUserHigh(
            Long userLow,
            Long userHigh
    );


    /*
     * ==========================================
     * 내 대화 목록
     *
     * updatedAt 최신순
     * ==========================================
     */

    Slice<DmConversation>
    findByUserLowOrUserHighOrderByUpdatedAtDescIdDesc(
            Long userLow,
            Long userHigh,
            Pageable pageable
    );


    /*
     * ==========================================
     * 대화 참여 여부 확인
     * ==========================================
     */

    @Query("""
            select c
            from DmConversation c
            where c.id = :conversationId
              and (
                    c.userLow = :userId
                    or c.userHigh = :userId
                  )
            """)
    Optional<DmConversation>
    findParticipantConversation(
            @Param("conversationId")
            Long conversationId,

            @Param("userId")
            Long userId
    );


    /*
     * ==========================================
     * 메시지 전송 / 읽음 처리 시
     * 대화방 row lock
     * ==========================================
     */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c
            from DmConversation c
            where c.id = :conversationId
              and (
                    c.userLow = :userId
                    or c.userHigh = :userId
                  )
            """)
    Optional<DmConversation>
    findParticipantConversationForUpdate(
            @Param("conversationId")
            Long conversationId,

            @Param("userId")
            Long userId
    );
}