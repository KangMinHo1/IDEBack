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
        name = "dm_conversations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_dm_pair",
                        columnNames = {
                                "user_low",
                                "user_high"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_dm_low_updated",
                        columnList = "user_low, updated_at"
                ),
                @Index(
                        name = "idx_dm_high_updated",
                        columnList = "user_high, updated_at"
                )
        }
)
public class DmConversation {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY
    )
    private Long id;


    /*
     * ==========================================
     * 참여 사용자
     *
     * 항상 작은 userId → userLow
     * 항상 큰 userId → userHigh
     * ==========================================
     */

    @Column(
            name = "user_low",
            nullable = false
    )
    private Long userLow;


    @Column(
            name = "user_high",
            nullable = false
    )
    private Long userHigh;


    /*
     * ==========================================
     * 대화가 시작된 게시글
     *
     * 게시글이 없어져도
     * 대화 자체는 유지하기 위해
     * Post 연관관계가 아니라 ID만 저장
     * ==========================================
     */

    @Column(
            name = "source_post_id"
    )
    private Long sourcePostId;


    @Column(
            name = "source_title",
            length = 200
    )
    private String sourceTitle;


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
            name = "updated_at",
            nullable = false
    )
    private LocalDateTime updatedAt;


    /*
     * ==========================================
     * 생성
     * ==========================================
     */

    public static DmConversation create(
            Long firstUserId,
            Long secondUserId,
            Long sourcePostId,
            String sourceTitle
    ) {

        if (
                firstUserId == null ||
                        secondUserId == null
        ) {
            throw new IllegalArgumentException(
                    "대화 참여자 ID가 필요합니다."
            );
        }


        if (firstUserId.equals(secondUserId)) {
            throw new IllegalArgumentException(
                    "같은 사용자끼리는 대화를 생성할 수 없습니다."
            );
        }


        DmConversation conversation =
                new DmConversation();


        conversation.userLow =
                Math.min(
                        firstUserId,
                        secondUserId
                );


        conversation.userHigh =
                Math.max(
                        firstUserId,
                        secondUserId
                );


        conversation.sourcePostId =
                sourcePostId;


        conversation.sourceTitle =
                sourceTitle;


        LocalDateTime now =
                LocalDateTime.now();


        conversation.createdAt =
                now;


        conversation.updatedAt =
                now;


        return conversation;
    }


    /*
     * ==========================================
     * 참여자인지 확인
     * ==========================================
     */

    public boolean containsUser(
            Long userId
    ) {

        if (userId == null) {
            return false;
        }


        return userId.equals(userLow) ||
                userId.equals(userHigh);
    }


    /*
     * ==========================================
     * 상대방 ID
     * ==========================================
     */

    public Long getOtherUserId(
            Long myUserId
    ) {

        if (myUserId == null) {

            throw new IllegalArgumentException(
                    "사용자 ID가 필요합니다."
            );
        }


        if (myUserId.equals(userLow)) {
            return userHigh;
        }


        if (myUserId.equals(userHigh)) {
            return userLow;
        }


        throw new IllegalArgumentException(
                "대화 참여자가 아닙니다."
        );
    }


    /*
     * ==========================================
     * 게시글 정보 갱신
     * ==========================================
     */

    public void updateSource(
            Long sourcePostId,
            String sourceTitle
    ) {

        this.sourcePostId =
                sourcePostId;


        this.sourceTitle =
                sourceTitle;


        touch();
    }


    /*
     * ==========================================
     * 최근 활동 시간 갱신
     * ==========================================
     */

    public void touch() {

        this.updatedAt =
                LocalDateTime.now();
    }


    /*
     * ==========================================
     * INSERT 직전 시간 보정
     * ==========================================
     */

    @PrePersist
    private void prePersist() {

        LocalDateTime now =
                LocalDateTime.now();


        if (createdAt == null) {
            createdAt = now;
        }


        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}