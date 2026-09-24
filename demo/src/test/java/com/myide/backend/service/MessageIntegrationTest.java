package com.myide.backend.service;

import com.myide.backend.config.QuerydslConfig;
import com.myide.backend.domain.User;
import com.myide.backend.domain.post.Post;
import com.myide.backend.repository.message.DirectMessageCommentRepository;
import com.myide.backend.repository.message.DirectMessagePostRepository;
import com.myide.backend.repository.message.DirectMessageUserRepository;
import com.myide.backend.repository.message.DmConversationRepository;
import com.myide.backend.repository.message.DmMessageRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 개인 메시지(DM) 기능 통합 테스트.
 *
 * 왜 다시 썼는가
 * -------------
 * 예전 버전은 DirectMessageService 가 JdbcTemplate 을 직접 쓰던 시절의 것이었다.
 * c0be93f 에서 서비스가 JPA 구조로 바뀌면서 생성자가 달라졌는데 이 테스트는 따라가지
 * 못해 컴파일부터 실패했고, 그 뒤로 ./gradlew test 가 통째로 막혀 있었다.
 *
 * 또 예전 버전은 테이블을 손으로 만든 뒤 message-schema.sql 을 test/resources 에
 * "복사해 두라"고 요구했다. 그 파일은 저장소에 없어서, 컴파일이 됐더라도 설정 단계에서
 * 멈췄을 것이다. 지금은 엔티티에서 스키마를 만들어 쓰므로 준비물이 따로 없다.
 *
 * H2 를 MySQL 모드로 여는 이유는 Post 엔티티가 LONGTEXT 를 쓰기 때문이다.
 * 기본 모드에서는 그 타입을 몰라 테이블 생성이 실패한다.
 */
@DataJpaTest
@Import(QuerydslConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dm-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class MessageIntegrationTest {

    @Autowired
    private DmConversationRepository conversationRepository;

    @Autowired
    private DmMessageRepository messageRepository;

    @Autowired
    private DirectMessageUserRepository userRepository;

    @Autowired
    private DirectMessagePostRepository postRepository;

    @Autowired
    private DirectMessageCommentRepository commentRepository;

    @Autowired
    private TestEntityManager entityManager;

    private DirectMessageService directMessages;

    /** 대화를 시작하는 쪽. */
    private Long meId;

    /** 대화 상대. 아래 게시글의 작성자이기도 하다. */
    private Long otherId;

    /** 이 대화에 끼어 있지 않은 제삼자. */
    private Long strangerId;

    /** 대화를 시작한 계기가 된 게시글. */
    private Long postId;

    @BeforeEach
    void setUp() {
        directMessages = new DirectMessageService(
                conversationRepository,
                messageRepository,
                userRepository,
                postRepository,
                commentRepository,

                /* 알림 발행은 이 테스트의 관심사가 아니라 받기만 하고 버린다. */
                event -> {
                }
        );

        meId = saveUser("first@example.com", "첫째");
        otherId = saveUser("second@example.com", "둘째");
        strangerId = saveUser("third@example.com", "셋째");

        postId = postRepository.save(
                Post.createNormalPost(otherId, "둘째", "글", "내용", "자유")
        ).getId();

        entityManager.flush();
    }

    private Long saveUser(String email, String nickname) {
        return userRepository.save(
                User.builder()
                        .email(email)
                        .password("encoded")
                        .nickname(nickname)
                        .build()
        ).getId();
    }

    /** 게시글을 통해 시작한 대화 하나를 만들고 그 id 를 돌려준다. */
    private long startConversation() {
        return Long.parseLong(
                directMessages.start(
                        meId,
                        new DirectMessageService.StartRequest(otherId, postId)
                ).id()
        );
    }

    private DirectMessageService.Message send(long conversationId, String content, String clientId) {
        return directMessages.send(
                meId,
                conversationId,
                new DirectMessageService.SendRequest(content, clientId)
        );
    }

    @Test
    @DisplayName("두 사람의 대화방은 하나뿐이고, 같은 clientId 로 다시 보내도 메시지가 늘지 않는다")
    void pairIsUniqueAndRetryDoesNotDuplicate() {
        long conversationId = startConversation();

        /* 반대편에서 다시 시작해도 같은 방이 나와야 한다. */
        long reverse = Long.parseLong(
                directMessages.start(
                        otherId,
                        new DirectMessageService.StartRequest(meId, null)
                ).id()
        );

        assertEquals(conversationId, reverse);

        /*
         * clientId 는 프론트가 만드는 전송 식별자다. 네트워크가 끊겨 같은 요청이
         * 두 번 도착해도 메시지가 두 개 쌓이면 안 된다.
         */
        String clientId = UUID.randomUUID().toString();

        DirectMessageService.Message first = send(conversationId, "안녕", clientId);

        assertEquals(first.id(), send(conversationId, "안녕", clientId).id());
        assertEquals(1, directMessages.history(otherId, conversationId, null).items().size());

        /* 같은 clientId 로 다른 내용을 보내는 것은 재전송이 아니라 충돌이다. */
        assertThrows(
                ResponseStatusException.class,
                () -> send(conversationId, "다른 내용", clientId)
        );
    }

    @Test
    @DisplayName("대화에 끼어 있지 않은 사람은 읽기도 쓰기도 읽음 처리도 할 수 없다")
    void nonParticipantCannotReadSendOrMarkRead() {
        long conversationId = startConversation();

        DirectMessageService.Message message =
                send(conversationId, "비공개", UUID.randomUUID().toString());

        assertThrows(
                ResponseStatusException.class,
                () -> directMessages.detail(strangerId, conversationId)
        );

        assertThrows(
                ResponseStatusException.class,
                () -> directMessages.history(strangerId, conversationId, null)
        );

        assertThrows(
                ResponseStatusException.class,
                () -> directMessages.send(
                        strangerId,
                        conversationId,
                        new DirectMessageService.SendRequest("침입", UUID.randomUUID().toString())
                )
        );

        assertThrows(
                ResponseStatusException.class,
                () -> directMessages.read(
                        strangerId,
                        conversationId,
                        new DirectMessageService.ReadRequest(Long.valueOf(message.id()))
                )
        );

        assertEquals(0, directMessages.list(strangerId, 0).items().size());
    }

    @Test
    @DisplayName("읽음 처리는 받은 사람의 메시지만, 지정한 지점까지만 처리한다")
    void readOnlyMarksReceiverMessagesThroughCursor() {
        long conversationId = startConversation();

        DirectMessageService.Message firstMessage =
                send(conversationId, "하나", UUID.randomUUID().toString());

        send(conversationId, "둘", UUID.randomUUID().toString());

        assertEquals(2, directMessages.unread(otherId));

        directMessages.read(
                otherId,
                conversationId,
                new DirectMessageService.ReadRequest(Long.valueOf(firstMessage.id()))
        );

        /* 커서까지만 읽혔으므로 뒤의 한 개가 남는다. */
        assertEquals(1, directMessages.unread(otherId));

        /* 보낸 사람 쪽에는 애초에 안 읽은 메시지가 없다. */
        assertEquals(0, directMessages.unread(meId));
    }

    @Test
    @DisplayName("이전 메시지를 이어 받을 때 빠지거나 겹치는 구간이 없다")
    void historyPaginationHasNoGap() {
        long conversationId = startConversation();

        for (int i = 0; i < 55; i++) {
            send(conversationId, "메시지 " + i, UUID.randomUUID().toString());
        }

        DirectMessageService.Slice<DirectMessageService.Message> latest =
                directMessages.history(meId, conversationId, null);

        assertEquals(50, latest.items().size());
        assertTrue(latest.hasMore());

        /* 첫 화면에서 가장 오래된 메시지를 기준으로 그 앞을 더 받아 온다. */
        long oldestOnFirstPage = Long.parseLong(latest.items().get(0).id());

        DirectMessageService.Slice<DirectMessageService.Message> older =
                directMessages.history(meId, conversationId, oldestOnFirstPage);

        assertEquals(5, older.items().size());
        assertFalse(older.hasMore());

        /* 이어 받은 마지막 메시지가 첫 화면의 첫 메시지보다 앞서야 겹침이 없다. */
        assertTrue(
                Long.parseLong(older.items().get(4).id()) < oldestOnFirstPage
        );
    }

    @Test
    @DisplayName("계기가 된 게시글이 지워져도 대화와 메시지는 남는다")
    void deletingSourcePostKeepsConversation() {
        long conversationId = startConversation();

        send(conversationId, "보존", UUID.randomUUID().toString());

        postRepository.deleteById(postId);
        entityManager.flush();

        assertEquals(1, directMessages.history(meId, conversationId, null).items().size());

        /*
         * 게시글 제목은 대화방에 복사해 두므로 글이 지워져도 남는다.
         * JdbcTemplate 시절에는 posts 를 조인해서 읽었기 때문에 이 값이 null 이 됐었다.
         * 지금은 값이 남는 쪽이 의도된 동작이라 그대로 확인한다.
         */
        assertNotNull(directMessages.detail(meId, conversationId).source());
    }
}
