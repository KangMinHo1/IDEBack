package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.UserRole;

import com.myide.backend.domain.message.DmConversation;
import com.myide.backend.domain.message.DmMessage;

import com.myide.backend.domain.post.Post;

import com.myide.backend.repository.message.DmConversationRepository;
import com.myide.backend.repository.message.DmMessageRepository;

import com.myide.backend.repository.message.DirectMessageCommentRepository;
import com.myide.backend.repository.message.DirectMessagePostRepository;
import com.myide.backend.repository.message.DirectMessageUserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;

import org.springframework.data.domain.PageRequest;

import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectMessageService {


    /*
     * =====================================================
     * Repository
     * =====================================================
     */

    private final DmConversationRepository
            conversationRepository;


    private final DmMessageRepository
            messageRepository;


    private final DirectMessageUserRepository
            userRepository;


    private final DirectMessagePostRepository
            postRepository;


    private final DirectMessageCommentRepository
            commentRepository;


    /*
     * =====================================================
     * Event
     * =====================================================
     */

    private final ApplicationEventPublisher events;


    /*
     * =====================================================
     * 기존 프론트 응답 형식 유지
     * =====================================================
     */

    public record Recipient(
            String id,
            String name
    ) {
    }


    public record Source(
            String postId,
            String title
    ) {
    }


    public record Message(
            String id,
            String conversationId,
            String senderId,
            String content,
            String createdAt,
            boolean mine,
            String readAt,
            String clientId
    ) {
    }


    public record Conversation(
            String id,
            Recipient recipient,
            Source source,
            String lastContent,
            String updatedAt,
            long unreadCount
    ) {
    }


    public record Slice<T>(
            List<T> items,
            boolean hasMore
    ) {
    }


    /*
     * =====================================================
     * Request
     * =====================================================
     */

    public record StartRequest(
            Long recipientId,
            Long postId
    ) {
    }


    public record SendRequest(
            String content,
            String clientId
    ) {
    }


    public record ReadRequest(
            Long throughId
    ) {
    }


    /*
     * =====================================================
     * WebSocket Refresh Event
     *
     * 기존 DirectMessageEvents에서 그대로 사용
     * =====================================================
     */

    public record Changed(
            Long first,
            Long second
    ) {
    }


    /*
     * =====================================================
     * 공통 예외
     * =====================================================
     */

    private ResponseStatusException error(
            HttpStatus status,
            String message
    ) {

        return new ResponseStatusException(
                status,
                message
        );
    }


    /*
     * =====================================================
     * 현재 사용자 검증
     *
     * DM은 일반 사용자만 사용
     * =====================================================
     */

    private User requireCurrentUser(
            Long userId
    ) {

        if (userId == null) {

            throw error(
                    HttpStatus.UNAUTHORIZED,
                    "일반 사용자 로그인이 필요합니다."
            );
        }


        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() ->
                                error(
                                        HttpStatus.UNAUTHORIZED,
                                        "일반 사용자 로그인이 필요합니다."
                                )
                        );


        if (user.getRole() != UserRole.USER) {

            throw error(
                    HttpStatus.UNAUTHORIZED,
                    "일반 사용자 로그인이 필요합니다."
            );
        }


        return user;
    }


    /*
     * =====================================================
     * 상대방 사용자 검증
     * =====================================================
     */

    private User requireRecipient(
            Long userId
    ) {

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() ->
                                error(
                                        HttpStatus.NOT_FOUND,
                                        "대화 상대를 찾을 수 없습니다."
                                )
                        );


        if (user.getRole() != UserRole.USER) {

            throw error(
                    HttpStatus.NOT_FOUND,
                    "대화 상대를 찾을 수 없습니다."
            );
        }


        return user;
    }


    /*
     * =====================================================
     * 대화 참여자 조회
     * =====================================================
     */

    private DmConversation participant(
            Long conversationId,
            Long myUserId,
            boolean lock
    ) {

        requireCurrentUser(
                myUserId
        );


        if (conversationId == null) {

            throw error(
                    HttpStatus.NOT_FOUND,
                    "대화를 찾을 수 없습니다."
            );
        }


        if (lock) {

            return conversationRepository
                    .findParticipantConversationForUpdate(
                            conversationId,
                            myUserId
                    )
                    .orElseThrow(() ->
                            error(
                                    HttpStatus.NOT_FOUND,
                                    "대화를 찾을 수 없습니다."
                            )
                    );
        }


        return conversationRepository
                .findParticipantConversation(
                        conversationId,
                        myUserId
                )
                .orElseThrow(() ->
                        error(
                                HttpStatus.NOT_FOUND,
                                "대화를 찾을 수 없습니다."
                        )
                );
    }


    /*
     * =====================================================
     * LocalDateTime → 기존 String 응답
     * =====================================================
     */

    private String time(
            LocalDateTime value
    ) {

        return value == null
                ? null
                : value.toString();
    }


    /*
     * =====================================================
     * Entity → Message DTO
     * =====================================================
     */

    private Message toMessage(
            DmMessage entity,
            Long myUserId
    ) {

        return new Message(
                String.valueOf(
                        entity.getId()
                ),

                String.valueOf(
                        entity
                                .getConversation()
                                .getId()
                ),

                String.valueOf(
                        entity.getSenderId()
                ),

                entity.getContent(),

                time(
                        entity.getCreatedAt()
                ),

                Objects.equals(
                        entity.getSenderId(),
                        myUserId
                ),

                time(
                        entity.getReadAt()
                ),

                entity.getClientId()
        );
    }


    /*
     * =====================================================
     * Entity → Conversation DTO
     * =====================================================
     */

    private Conversation toConversation(
            DmConversation entity,
            Long myUserId
    ) {

        Long otherUserId =
                entity.getOtherUserId(
                        myUserId
                );


        /*
         * 상대방이 탈퇴한 경우에도
         * 기존 대화는 유지
         */
        String recipientName =
                userRepository
                        .findById(otherUserId)
                        .map(User::getNickname)
                        .orElse(
                                "탈퇴한 사용자"
                        );


        String lastContent =
                messageRepository
                        .findFirstByConversation_IdOrderByIdDesc(
                                entity.getId()
                        )
                        .map(DmMessage::getContent)
                        .orElse(null);


        long unreadCount =
                messageRepository
                        .countByConversation_IdAndReceiverIdAndReadAtIsNull(
                                entity.getId(),
                                myUserId
                        );


        Source source =
                null;


        if (entity.getSourcePostId() != null) {

            source =
                    new Source(
                            String.valueOf(
                                    entity.getSourcePostId()
                            ),

                            entity.getSourceTitle()
                    );
        }


        return new Conversation(
                String.valueOf(
                        entity.getId()
                ),

                new Recipient(
                        String.valueOf(
                                otherUserId
                        ),

                        recipientName
                ),

                source,

                lastContent,

                time(
                        entity.getUpdatedAt()
                ),

                unreadCount
        );
    }


    /*
     * =====================================================
     * 대화 목록
     *
     * GET /api/messages/conversations?page=0
     * =====================================================
     */

    public Slice<Conversation> list(
            Long myUserId,
            int page
    ) {

        requireCurrentUser(
                myUserId
        );


        if (
                page < 0 ||
                        page > 100000
        ) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "페이지 값이 올바르지 않습니다."
            );
        }


        /*
         * Spring Data Slice를 사용하면
         * 현재 페이지 다음 데이터 존재 여부를
         * 자동으로 확인 가능
         *
         * 기존처럼 50개 단위
         */
        org.springframework.data.domain.Slice<DmConversation>
                result =
                conversationRepository
                        .findByUserLowOrUserHighOrderByUpdatedAtDescIdDesc(
                                myUserId,
                                myUserId,
                                PageRequest.of(
                                        page,
                                        50
                                )
                        );


        List<Conversation> items =
                result
                        .getContent()
                        .stream()
                        .map(conversation ->
                                toConversation(
                                        conversation,
                                        myUserId
                                )
                        )
                        .toList();


        return new Slice<>(
                items,
                result.hasNext()
        );
    }


    /*
     * =====================================================
     * 대화 상세
     *
     * GET /api/messages/conversations/{id}
     * =====================================================
     */

    public Conversation detail(
            Long myUserId,
            long conversationId
    ) {

        DmConversation conversation =
                participant(
                        conversationId,
                        myUserId,
                        false
                );


        return toConversation(
                conversation,
                myUserId
        );
    }


    /*
     * =====================================================
     * 전체 안 읽은 메시지 수
     *
     * GET /api/messages/unread
     * =====================================================
     */

    public long unread(
            Long myUserId
    ) {

        requireCurrentUser(
                myUserId
        );


        return messageRepository
                .countByReceiverIdAndReadAtIsNull(
                        myUserId
                );
    }


    /*
     * =====================================================
     * 대화 시작
     *
     * POST /api/messages/conversations
     * =====================================================
     */

    @Transactional
    public Conversation start(
            Long myUserId,
            StartRequest request
    ) {

        requireCurrentUser(
                myUserId
        );


        if (
                request == null ||
                        request.recipientId() == null ||
                        request.recipientId() <= 0 ||
                        request.recipientId().equals(myUserId)
        ) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "다른 사용자를 선택해 주세요."
            );
        }


        Long otherUserId =
                request.recipientId();


        Long low =
                Math.min(
                        myUserId,
                        otherUserId
                );


        Long high =
                Math.max(
                        myUserId,
                        otherUserId
                );


        /*
         * ======================================
         * 사용자 두 명을 ID 순서대로 LOCK
         *
         * 동시에 양쪽에서 대화를 만들어도
         * 같은 pair 대화가 중복 생성되지 않도록 함
         * ======================================
         */

        List<User> users =
                userRepository
                        .findAllByIdInForUpdate(
                                List.of(
                                        low,
                                        high
                                )
                        );


        if (users.size() != 2) {

            throw error(
                    HttpStatus.NOT_FOUND,
                    "대화 상대를 찾을 수 없습니다."
            );
        }


        boolean hasInvalidUser =
                users
                        .stream()
                        .anyMatch(user ->
                                user.getRole() !=
                                        UserRole.USER
                        );


        if (hasInvalidUser) {

            throw error(
                    HttpStatus.NOT_FOUND,
                    "대화 상대를 찾을 수 없습니다."
            );
        }


        /*
         * ======================================
         * 게시글/댓글을 통해 시작한 DM인지 확인
         * ======================================
         */

        Long sourcePostId =
                null;


        String sourceTitle =
                null;


        if (request.postId() != null) {

            if (request.postId() <= 0) {

                throw error(
                        HttpStatus.BAD_REQUEST,
                        "대화 상대와 관련된 게시글을 찾을 수 없습니다."
                );
            }


            Post post =
                    postRepository
                            .findById(
                                    request.postId()
                            )
                            .orElseThrow(() ->
                                    error(
                                            HttpStatus.BAD_REQUEST,
                                            "대화 상대와 관련된 게시글을 찾을 수 없습니다."
                                    )
                            );


            /*
             * 공지사항에서는 사용자 DM 시작 금지
             */
            if (post.isNotice()) {

                throw error(
                        HttpStatus.BAD_REQUEST,
                        "대화 상대와 관련된 게시글을 찾을 수 없습니다."
                );
            }


            /*
             * 상대방이
             *
             * 1. 게시글 작성자이거나
             * 2. 해당 게시글 댓글 작성자여야 함
             */

            boolean postAuthor =
                    Objects.equals(
                            post.getAuthorId(),
                            otherUserId
                    );


            boolean commentAuthor =
                    commentRepository
                            .existsByPost_IdAndAuthorId(
                                    post.getId(),
                                    otherUserId
                            );


            if (
                    !postAuthor &&
                            !commentAuthor
            ) {

                throw error(
                        HttpStatus.BAD_REQUEST,
                        "대화 상대와 관련된 게시글을 찾을 수 없습니다."
                );
            }


            sourcePostId =
                    post.getId();


            sourceTitle =
                    post.getTitle();
        }


        /*
         * ======================================
         * 기존 대화 확인
         * ======================================
         */

        DmConversation conversation =
                conversationRepository
                        .findByUserLowAndUserHigh(
                                low,
                                high
                        )
                        .orElse(null);


        /*
         * ======================================
         * 없으면 생성
         * ======================================
         */

        if (conversation == null) {

            conversation =
                    DmConversation.create(
                            low,
                            high,
                            sourcePostId,
                            sourceTitle
                    );


            conversation =
                    conversationRepository.save(
                            conversation
                    );

        } else if (sourcePostId != null) {

            /*
             * 이미 대화가 있는데
             * 다른 게시글에서 다시 쪽지를 누른 경우
             * 최근 진입 게시글 정보 갱신
             */

            conversation.updateSource(
                    sourcePostId,
                    sourceTitle
            );
        }


        /*
         * commit 성공 후
         * 두 사용자 WebSocket에 refresh 신호
         */

        events.publishEvent(
                new Changed(
                        myUserId,
                        otherUserId
                )
        );


        return toConversation(
                conversation,
                myUserId
        );
    }


    /*
     * =====================================================
     * 메시지 기록 조회
     *
     * GET
     * /api/messages/conversations/{id}/messages
     *
     * before = 이전 마지막 메시지 ID
     * =====================================================
     */

    public Slice<Message> history(
            Long myUserId,
            long conversationId,
            Long before
    ) {

        participant(
                conversationId,
                myUserId,
                false
        );


        long beforeId =
                before == null
                        ? Long.MAX_VALUE
                        : before;


        /*
         * 기존 코드와 동일하게
         * 51개 조회해서
         * 50개 초과 여부 확인
         */
        List<DmMessage> rows =
                messageRepository
                        .findByConversation_IdAndIdLessThanOrderByIdDesc(
                                conversationId,
                                beforeId,
                                PageRequest.of(
                                        0,
                                        51
                                )
                        );


        boolean hasMore =
                rows.size() > 50;


        List<DmMessage> limited =
                new ArrayList<>(
                        rows.subList(
                                0,
                                Math.min(
                                        50,
                                        rows.size()
                                )
                        )
                );


        /*
         * DB에서는 최신 → 과거로 조회했으므로
         * 프론트에는 과거 → 최신 순서 반환
         */
        Collections.reverse(
                limited
        );


        List<Message> items =
                limited
                        .stream()
                        .map(message ->
                                toMessage(
                                        message,
                                        myUserId
                                )
                        )
                        .toList();


        return new Slice<>(
                items,
                hasMore
        );
    }


    /*
     * =====================================================
     * 메시지 전송
     *
     * POST
     * /api/messages/conversations/{id}/messages
     * =====================================================
     */

    @Transactional
    public Message send(
            Long myUserId,
            long conversationId,
            SendRequest request
    ) {

        /*
         * 대화방 row lock
         */
        DmConversation conversation =
                participant(
                        conversationId,
                        myUserId,
                        true
                );


        /*
         * ======================================
         * 메시지 내용 검사
         * ======================================
         */

        String content =
                request == null ||
                        request.content() == null
                        ? ""
                        : request
                        .content()
                        .trim();


        if (
                content.isEmpty() ||
                        content.length() > 2000
        ) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "메시지는 1~2000자로 입력해 주세요."
            );
        }


        /*
         * ======================================
         * clientId 검사
         * ======================================
         */

        String clientId =
                request.clientId();


        try {

            if (
                    clientId == null ||
                            !UUID
                                    .fromString(clientId)
                                    .toString()
                                    .equalsIgnoreCase(
                                            clientId
                                    )
            ) {

                throw new IllegalArgumentException();
            }

        } catch (IllegalArgumentException e) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "전송 식별자가 올바르지 않습니다."
            );
        }


        /*
         * ======================================
         * 같은 clientId가 이미 저장되었는지 확인
         *
         * 프론트 재전송 시 중복 저장 방지
         * ======================================
         */

        DmMessage previous =
                messageRepository
                        .findBySenderIdAndClientId(
                                myUserId,
                                clientId
                        )
                        .orElse(null);


        if (previous != null) {

            Message oldMessage =
                    toMessage(
                            previous,
                            myUserId
                    );


            boolean sameConversation =
                    Objects.equals(
                            previous
                                    .getConversation()
                                    .getId(),

                            conversationId
                    );


            boolean sameContent =
                    Objects.equals(
                            previous.getContent(),
                            content
                    );


            if (
                    !sameConversation ||
                            !sameContent
            ) {

                throw error(
                        HttpStatus.CONFLICT,
                        "전송 식별자가 이미 사용되었습니다."
                );
            }


            /*
             * 완전히 같은 재전송이면
             * 새 INSERT 하지 않고 기존 메시지 반환
             */
            return oldMessage;
        }


        /*
         * ======================================
         * 상대방
         * ======================================
         */

        Long otherUserId =
                conversation
                        .getOtherUserId(
                                myUserId
                        );


        requireRecipient(
                otherUserId
        );


        /*
         * ======================================
         * 메시지 저장
         * ======================================
         */

        DmMessage message =
                DmMessage.create(
                        conversation,
                        myUserId,
                        otherUserId,
                        clientId,
                        content
                );


        DmMessage savedMessage =
                messageRepository.save(
                        message
                );


        /*
         * 대화방을 목록 상단으로 올림
         */
        conversation.touch();


        /*
         * WebSocket refresh
         */
        events.publishEvent(
                new Changed(
                        myUserId,
                        otherUserId
                )
        );


        return toMessage(
                savedMessage,
                myUserId
        );
    }


    /*
     * =====================================================
     * 읽음 처리
     *
     * POST
     * /api/messages/conversations/{id}/read
     * =====================================================
     */

    @Transactional
    public void read(
            Long myUserId,
            long conversationId,
            ReadRequest request
    ) {

        /*
         * 같은 대화에서 동시에 read/send 등이
         * 실행되는 상황을 정리하기 위해 lock
         */
        DmConversation conversation =
                participant(
                        conversationId,
                        myUserId,
                        true
                );


        /*
         * ======================================
         * throughId 검사
         * ======================================
         */

        if (
                request == null ||
                        request.throughId() == null ||
                        request.throughId() <= 0
        ) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "읽은 메시지 기준을 확인해 주세요."
            );
        }


        boolean exists =
                messageRepository
                        .existsByIdAndConversation_Id(
                                request.throughId(),
                                conversationId
                        );


        if (!exists) {

            throw error(
                    HttpStatus.BAD_REQUEST,
                    "읽은 메시지 기준을 확인해 주세요."
            );
        }


        /*
         * ======================================
         * 나에게 온 메시지만 읽음 처리
         *
         * throughId 이하
         * ======================================
         */

        int changed =
                messageRepository
                        .markReadThrough(
                                conversationId,
                                myUserId,
                                request.throughId(),
                                LocalDateTime.now()
                        );


        /*
         * 실제 변경이 있을 때만
         * 양쪽 화면 refresh
         */
        if (changed > 0) {

            Long otherUserId =
                    conversation
                            .getOtherUserId(
                                    myUserId
                            );


            events.publishEvent(
                    new Changed(
                            myUserId,
                            otherUserId
                    )
            );
        }
    }
}