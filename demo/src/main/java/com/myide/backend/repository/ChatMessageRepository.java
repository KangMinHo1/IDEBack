package com.myide.backend.repository;

import com.myide.backend.domain.chat.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // 💡 [수정] 모두에게 보낸 메시지(receiverId IS NULL)와 나와 관련된 1:1 메시지만 가져옵니다!
    @Query("SELECT c FROM ChatMessage c WHERE c.workspaceId = :workspaceId " +
            "AND (c.receiverId IS NULL OR c.receiverId = :userId OR c.senderId = :userId) " +
            "ORDER BY c.createdAt ASC")
    List<ChatMessage> findChatHistory(@Param("workspaceId") String workspaceId, @Param("userId") Long userId);
}