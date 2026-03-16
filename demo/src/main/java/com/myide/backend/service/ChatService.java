package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.chat.ChatMessage;
import com.myide.backend.dto.chat.ChatMessageRequest;
import com.myide.backend.dto.chat.ChatMessageResponse;
import com.myide.backend.repository.ChatMessageRepository;
import com.myide.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository; // 💡 닉네임 버그 방지용 (필수!)

    @Transactional
    public ChatMessageResponse saveMessage(ChatMessageRequest request) {
        // 💡 프론트엔드가 보낸 가짜 이름 무시하고 DB에서 진짜 유저 정보 찾기
        User sender = userRepository.findById(request.getSenderId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다."));

        ChatMessage message = ChatMessage.builder()
                .workspaceId(request.getWorkspaceId())
                .senderId(request.getSenderId())
                .senderName(sender.getNickname()) // 진짜 닉네임 주입
                .receiverId(request.getReceiverId()) // 💡 [추가] 1:1 채팅용 수신자 ID 저장
                .content(request.getContent())
                .type(request.getType())
                .build();

        ChatMessage savedMessage = chatMessageRepository.save(message);
        return ChatMessageResponse.from(savedMessage);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getChatHistory(String workspaceId, Long userId) { // 💡 userId 파라미터 추가
        return chatMessageRepository.findChatHistory(workspaceId, userId)
                .stream()
                .map(ChatMessageResponse::from)
                .collect(Collectors.toList());
    }
}